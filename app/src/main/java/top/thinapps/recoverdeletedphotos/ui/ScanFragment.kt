package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.RadioButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentScanBinding
import top.thinapps.recoverdeletedphotos.scan.MediaScanner

class ScanFragment : Fragment() {

    private enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    private var _vb: FragmentScanBinding? = null
    private val vb get() = _vb!!
    private var job: Job? = null
    private val vm: ScanViewModel by activityViewModels()

    // keep references so we can cancel animations on destroy
    private val runningAnims = mutableListOf<ObjectAnimator>()
    private var countAnimator: ValueAnimator? = null

    // timing knobs
    private val COUNT_ANIM_MS = 3800L
    private val POST_ANIM_DWELL_MS = 1200L
    private val PULSE_CYCLE_MS = 2800L

    // gate so we don’t navigate until the animation + dwell completes
    private lateinit var countAnimDone: CompletableDeferred<Unit>

    // start/nav guards
    private var started = false
    private var navigating = false

    // permission launcher (requests are built from the selected type)
    private val requestPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val type = currentType()
        val wanted = requiredPerms(type)
        val granted = wanted.all { result[it] == true }
        if (granted) {
            if (!started) { started = true; start(type) }
        } else {
            showPermissionState()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentScanBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vb.cancelButton.setOnClickListener { cancel() }

        // default selection (Photos)
        vb.typePhotos?.isChecked = true

        // when the user switches type (before starting), reflect permission state
        listOfNotNull(vb.typePhotos, vb.typeVideos, vb.typeAudio).forEach { rb: RadioButton ->
            rb.setOnCheckedChangeListener { _, _ ->
                if (!started) {
                    val t = currentType()
                    if (!hasPermission(t)) showPermissionState() else showScanUI(true)
                }
            }
        }

        val t = currentType()
        if (hasPermission(t)) {
            if (!started) { started = true; start(t) }
        } else {
            showPermissionState()
        }
    }

    private fun currentType(): TypeChoice = when {
        vb.typeVideos?.isChecked == true -> TypeChoice.VIDEOS
        vb.typeAudio?.isChecked == true  -> TypeChoice.AUDIO
        else                             -> TypeChoice.PHOTOS
    }

    // ---- permissions ----

    private fun requiredPerms(type: TypeChoice): Array<String> {
        return if (Build.VERSION.SDK_INT < 33) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            when (type) {
                TypeChoice.PHOTOS -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                TypeChoice.VIDEOS -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                TypeChoice.AUDIO  -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
    }

    private fun hasPermission(type: TypeChoice): Boolean {
        val perms = requiredPerms(type)
        return perms.all { p ->
            ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun permanentlyDenied(type: TypeChoice): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            val show = shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
            return !show && !hasPermission(type)
        }
        val perms = requiredPerms(type)
        val anyShow = perms.any { shouldShowRequestPermissionRationale(it) }
        return !anyShow && !hasPermission(type)
    }

    // ---- flow ----

    private fun start(type: TypeChoice) {
        showScanUI(true)
        // view-tied scope prevents leaks if user leaves mid-scan
        job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                // init ui
                vb.totalLabel.text = getString(R.string.total_files_label)
                vb.totalCount.text = getString(R.string.total_files_count, 0)
                countAnimDone = CompletableDeferred()

                // cosmetic pulse (two rings, out of phase)
                startPulses()

                // run scan on io; use first total to kick the counter animation
                val items = withContext(Dispatchers.IO) {
                    var totalSeen = 0
                    MediaScanner(requireContext().applicationContext).scan(
                        includeImages = type == TypeChoice.PHOTOS,
                        includeVideos = type == TypeChoice.VIDEOS,
                        includeAudio  = type == TypeChoice.AUDIO
                    ) { _, total ->
                        if (totalSeen == 0 && total > 0) {
                            totalSeen = total
                            // launch animator on main, bound to lifecycle
                            viewLifecycleOwner.lifecycleScope.launch {
                                animateCountTo(totalSeen)
                            }
                        }
                    }
                }

                // no media found state
                if (items.isEmpty()) {
                    stopPulses()
                    showNoMediaState()
                    return@launch
                }

                // pass results to vm for next screen
                vm.results = items

                // wait for count animation + dwell before navigating
                countAnimDone.await()

                // navigate if still on this fragment with guard
                val current = findNavController().currentDestination?.id
                if (!navigating && isResumed && current == R.id.scanFragment) {
                    navigating = true
                    vb.cancelButton.isEnabled = false
                    val opts = NavOptions.Builder()
                        .setPopUpTo(R.id.scanFragment, true)
                        .build()
                    findNavController().navigate(R.id.action_scan_to_results, null, opts)
                }
            } catch (t: Throwable) {
                // ignore user-initiated cancel
                if (t is CancellationException) return@launch
                stopPulses()
                showErrorState()
            }
        }
    }

    private suspend fun animateCountTo(target: Int) {
        // cancel any previous animator to avoid overlap
        countAnimator?.cancel()

        // smooth ease-out count using valueanimator
        countAnimator = ValueAnimator.ofInt(0, target).apply {
            duration = COUNT_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Int
                vb.totalCount.text = getString(R.string.total_files_count, v)
            }
            doOnEnd {
                // linger so the number registers, then unblock navigation
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(POST_ANIM_DWELL_MS)
                    if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                }
            }
        }
        countAnimator?.start()
    }

    private fun startPulses() {
        // two repeating halo pulses (scale + fade) out of phase
        fun pulse(view: View, delayMs: Long) {
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.6f).apply {
                duration = PULSE_CYCLE_MS
                interpolator = FastOutSlowInInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                startDelay = delayMs
            }
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.6f).apply {
                duration = PULSE_CYCLE_MS
                interpolator = FastOutSlowInInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                startDelay = delayMs
            }
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 0.22f, 0f).apply {
                duration = PULSE_CYCLE_MS
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                startDelay = delayMs
            }
            runningAnims += listOf(scaleX, scaleY, alpha)
            scaleX.start(); scaleY.start(); alpha.start()
        }
        pulse(vb.pulse1, 0)
        pulse(vb.pulse2, PULSE_CYCLE_MS / 2)
    }

    private fun stopPulses() {
        // cancel animations and reset halo views
        runningAnims.forEach { it.cancel() }
        runningAnims.clear()
        countAnimator?.cancel()
        countAnimator = null
        vb.pulse1.alpha = 0f
        vb.pulse1.scaleX = 1f
        vb.pulse1.scaleY = 1f
        vb.pulse2.alpha = 0f
        vb.pulse2.scaleX = 1f
        vb.pulse2.scaleY = 1f
    }

    private fun cancel() {
        // stop scan and animations then deterministically go home
        job?.cancel()
        stopPulses()
        val nav = findNavController()
        if (!navigating) {
            navigating = true
            vb.cancelButton.isEnabled = false
            if (!nav.popBackStack(R.id.homeFragment, false)) {
                nav.navigate(
                    R.id.homeFragment,
                    null,
                    NavOptions.Builder().setPopUpTo(R.id.homeFragment, false).build()
                )
            }
        }
    }

    // ---- UI state toggles ----

    private fun showScanUI(show: Boolean) {
        vb.scanContent.visibility = if (show) View.VISIBLE else View.GONE
        vb.stateContainer.visibility = if (show) View.GONE else View.VISIBLE
        vb.cancelButton.isEnabled = show && !navigating
    }

    private fun showPermissionState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.perm_required_title)
        vb.stateMessage.text = getString(R.string.perm_required_msg)
        val type = currentType()
        vb.statePrimary.text = if (permanentlyDenied(type)) getString(R.string.open_settings) else getString(R.string.perm_grant)
        vb.statePrimary.setOnClickListener {
            if (permanentlyDenied(type)) {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(i)
            } else {
                requestPerm.launch(requiredPerms(type))
            }
        }
        vb.stateSecondary.apply {
            text = getString(R.string.go_home)
            visibility = View.VISIBLE
            setOnClickListener { findNavController().popBackStack(R.id.homeFragment, false) }
        }
    }

    private fun showNoMediaState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.no_media_title)
        vb.stateMessage.text = getString(R.string.no_media_msg)
        vb.statePrimary.text = getString(R.string.retry)
        vb.statePrimary.setOnClickListener {
            val t = currentType()
            if (hasPermission(t)) {
                if (!started) { started = true }
                start(t)
            } else showPermissionState()
        }
        vb.stateSecondary.apply {
            text = getString(R.string.go_home)
            visibility = View.VISIBLE
            setOnClickListener { findNavController().popBackStack(R.id.homeFragment, false) }
        }
    }

    private fun showErrorState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.scan_error_title)
        vb.stateMessage.text = getString(R.string.scan_error_msg)
        vb.statePrimary.text = getString(R.string.retry)
        vb.statePrimary.setOnClickListener {
            val t = currentType()
            if (hasPermission(t)) {
                if (!started) { started = true }
                start(t)
            } else showPermissionState()
        }
        vb.stateSecondary.apply {
            text = getString(R.string.go_home)
            visibility = View.VISIBLE
            setOnClickListener { findNavController().popBackStack(R.id.homeFragment, false) }
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        stopPulses()
        _vb = null
        super.onDestroyView()
    }
}
