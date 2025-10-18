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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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
    // safe binding for async callbacks/animators
    private inline fun withVb(block: FragmentScanBinding.() -> Unit) { _vb?.let(block) }

    private var job: Job? = null
    private val vm: ScanViewModel by activityViewModels()

    // animations
    private val runningAnims = mutableListOf<ObjectAnimator>()
    private var countAnimator: ValueAnimator? = null

    // timing (ms)
    private companion object {
        private const val COUNT_ANIM_MS = 3_800L
        private const val POST_ANIM_DWELL_MS = 1_200L
        private const val PULSE_CYCLE_MS = 2_800L
    }

    // gates
    private lateinit var countAnimDone: CompletableDeferred<Unit>
    private var started = false
    private var navigating = false

    // media type from Home (default PHOTOS)
    private val selectedType: TypeChoice by lazy {
        when (arguments?.getString("type")) {
            "VIDEOS" -> TypeChoice.VIDEOS
            "AUDIO" -> TypeChoice.AUDIO
            else -> TypeChoice.PHOTOS
        }
    }

    // single-permission launcher (Android 13+ only)
    private val requestPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (!started) {
                    started = true
                    start(selectedType)
                }
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

        // skip re-scan if results already exist
        if (vm.results.isNotEmpty()) {
            if (!navigating) {
                navigating = true
                vb.cancelButton.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launchWhenResumed {
                    val opts = NavOptions.Builder().setPopUpTo(R.id.scanFragment, true).build()
                    findNavController().navigate(R.id.action_scan_to_results, null, opts)
                }
            }
            return
        }

        // only support android 13+ where granular media permissions exist
        if (!isAndroid13Plus()) {
            showNotSupportedState()
            return
        }

        val t = selectedType
        val perm = requiredPerm(t)
        if (hasPermission(perm)) {
            if (!started) {
                started = true
                start(t)
            }
        } else {
            showPermissionState()
        }
    }

    // ---------- permissions (Android 13+) ----------

    private fun isAndroid13Plus(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(33)
    private fun requiredPerm(type: TypeChoice): String {
        return when (type) {
            TypeChoice.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
            TypeChoice.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
            TypeChoice.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
        }
    }

    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun permanentlyDenied(perm: String): Boolean {
        val shouldShow = shouldShowRequestPermissionRationale(perm)
        return !shouldShow && !hasPermission(perm)
    }

    // ---------- flow ----------

    private fun start(type: TypeChoice) {
        showScanUI(true)

        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // init UI
                vb.totalLabel.text = when (type) {
                    TypeChoice.PHOTOS -> getString(R.string.total_photos_label)
                    TypeChoice.VIDEOS -> getString(R.string.total_videos_label)
                    TypeChoice.AUDIO -> getString(R.string.total_audio_label)
                }
                vb.totalCount.text = getString(R.string.total_files_count, 0)

                countAnimDone = CompletableDeferred()
                startPulses()

                // MediaStore scan (includes trashed on API 30+, excludes pending)
                val items = withContext(Dispatchers.IO) {
                    var totalSeen = 0
                    MediaScanner(requireContext().applicationContext).scan(
                        includeImages = type == TypeChoice.PHOTOS,
                        includeVideos = type == TypeChoice.VIDEOS,
                        includeAudio = type == TypeChoice.AUDIO
                    ) { _, total ->
                        if (totalSeen == 0 && total > 0) {
                            totalSeen = total
                            viewLifecycleOwner.lifecycleScope.launch { animateCountTo(totalSeen) }
                        }
                    }
                }

                if (items.isEmpty()) {
                    showNoMediaState()
                    return@launch
                }

                vm.results = items

                // wait for the count animation to finish (short dwell)
                countAnimDone.await()

                if (!navigating) {
                    navigating = true
                    vb.cancelButton.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launchWhenResumed {
                        val current = findNavController().currentDestination?.id
                        if (current == R.id.scanFragment) {
                            val opts = NavOptions.Builder()
                                .setPopUpTo(R.id.scanFragment, true)
                                .build()
                            findNavController().navigate(R.id.action_scan_to_results, null, opts)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                showErrorState()
            } finally {
                // ensure animations are stopped and allow explicit retry
                stopPulses()
                started = false
            }
        }
    }

    private suspend fun animateCountTo(target: Int) {
        countAnimator?.cancel()
        countAnimator = ValueAnimator.ofInt(0, target).apply {
            duration = COUNT_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Int
                withVb {
                    totalCount.text = getString(R.string.total_files_count, v)
                }
            }
            doOnEnd {
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(POST_ANIM_DWELL_MS)
                    if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                }
            }
        }
        countAnimator?.start()
    }

    private fun startPulses() {
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
        runningAnims.forEach { it.cancel() }
        runningAnims.clear()
        countAnimator?.cancel()
        countAnimator = null

        withVb {
            pulse1.alpha = 0f
            pulse1.scaleX = 1f
            pulse1.scaleY = 1f

            pulse2.alpha = 0f
            pulse2.scaleX = 1f
            pulse2.scaleY = 1f
        }
    }

    private fun cancel() {
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

    // ---------- UI states ----------

    private fun showScanUI(show: Boolean) {
        vb.scanContent.visibility = if (show) View.VISIBLE else View.GONE
        vb.stateContainer.visibility = if (show) View.GONE else View.VISIBLE
        vb.cancelButton.isEnabled = show && !navigating
    }

    private fun showNotSupportedState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.android_13_required_title)
        vb.stateMessage.text = getString(R.string.android_13_required_msg)
        vb.statePrimary.text = getString(R.string.go_home)
        vb.statePrimary.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }
        vb.stateSecondary.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }
    }

    private fun showPermissionState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.perm_required_title)
        vb.stateMessage.text = getString(R.string.perm_required_msg)

        val perm = requiredPerm(selectedType) // 33+
        vb.statePrimary.text =
            if (permanentlyDenied(perm)) getString(R.string.open_settings)
            else getString(R.string.perm_grant)

        vb.statePrimary.setOnClickListener {
            if (permanentlyDenied(perm)) {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(i)
            } else {
                requestPerm.launch(perm)
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
            if (!isAndroid13Plus()) {
                showNotSupportedState()
                return@setOnClickListener
            }
            val t = selectedType
            val perm = requiredPerm(t)
            if (hasPermission(perm)) {
                if (!started) started = true
                start(t)
            } else {
                showPermissionState()
            }
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
            if (!isAndroid13Plus()) {
                showNotSupportedState()
                return@setOnClickListener
            }
            val t = selectedType
            val perm = requiredPerm(t)
            if (hasPermission(perm)) {
                if (!started) started = true
                start(t)
            } else {
                showPermissionState()
            }
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
