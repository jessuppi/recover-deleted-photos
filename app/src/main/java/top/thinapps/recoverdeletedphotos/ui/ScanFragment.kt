package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

    private var _vb: FragmentScanBinding? = null
    private val vb get() = _vb!!

    private val vm: ScanViewModel by activityViewModels()

    // state flags
    private var navigating = false
    private var started = false
    private var selectedType: TypeChoice = TypeChoice.PHOTOS

    // active animations
    private val runningAnims = mutableListOf<ObjectAnimator>()
    private var countAnimator: ValueAnimator? = null

    // timing constants
    private companion object {
        private const val COUNT_ANIM_MS = 3800L
        private const val POST_ANIM_DWELL_MS = 1200L
        private const val PULSE_CYCLE_MS = 2800L
    }

    // coroutine job for cancel control
    private var job: Job? = null

    enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // read selected media type from nav args
        selectedType = when (arguments?.getString("type")) {
            "VIDEOS" -> TypeChoice.VIDEOS
            "AUDIO" -> TypeChoice.AUDIO
            else -> TypeChoice.PHOTOS
        }
    }

    // permission launcher for android 13+
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

        // skip if already have results
        if (vm.results.isNotEmpty()) {
            if (!navigating) {
                navigating = true
                vb.cancelButton.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        val opts = NavOptions.Builder().setPopUpTo(R.id.scanFragment, true).build()
                        findNavController().navigate(R.id.action_scan_to_results, null, opts)
                    }
                }
            }
            return
        }

        // feature only for android 13+
        if (!isAndroid13Plus()) {
            showNotSupportedState()
            return
        }

        // check permission for selected type
        val perm = requiredPerm(selectedType)
        val granted = ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (!started) {
                started = true
                start(selectedType)
            }
        } else {
            showPermissionState()
        }
    }

    private fun isAndroid13Plus(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(33)
    private fun requiredPerm(type: TypeChoice): String = when (type) {
        TypeChoice.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
        TypeChoice.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
        TypeChoice.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
    }

    private fun hasPermission(type: TypeChoice): Boolean {
        if (!isAndroid13Plus()) return false
        val p = requiredPerm(type)
        return ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED
    }

    // tracks completion of count animation
    private var countAnimDone = CompletableDeferred<Unit>()

    // starts the scanning process
    private fun start(type: TypeChoice) = withVb {
        showScanUI(true)

        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // recheck permission before scan
                if (!hasPermission(type)) {
                    showPermissionState()
                    stopPulses()
                    return@launch
                }

                // set initial labels
                vb.totalLabel.text = when (type) {
                    TypeChoice.PHOTOS -> getString(R.string.total_photos_label)
                    TypeChoice.VIDEOS -> getString(R.string.total_videos_label)
                    TypeChoice.AUDIO -> getString(R.string.total_audio_label)
                }
                vb.totalCount.text = getString(R.string.total_files_count, 0)
                // reset color at start in case a previous run painted it green
                vb.totalCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_surface))

                countAnimDone = CompletableDeferred()
                startPulses()

                // run mediascanner off main thread
                val items = runCatching {
                    withContext(Dispatchers.IO) {
                        var lastTotal = 0
                        var lastUiUpdate = 0L
                        MediaScanner(requireContext().applicationContext).scan(
                            includeImages = type == TypeChoice.PHOTOS,
                            includeVideos = type == TypeChoice.VIDEOS,
                            includeAudio = type == TypeChoice.AUDIO
                        ) { _, total ->
                            // cheap progress updates: text only, throttled to ~5 fps
                            val now = SystemClock.uptimeMillis()
                            if (total != lastTotal && now - lastUiUpdate >= 200L) {
                                lastTotal = total
                                lastUiUpdate = now
                                vb.root.post {
                                    vb.totalCount.text = getString(R.string.total_files_count, total)
                                }
                            }
                        }
                    }
                }.getOrElse {
                    if (it is SecurityException) {
                        showPermissionState()
                    } else {
                        showErrorState()
                    }
                    stopPulses()
                    return@launch
                }

                // final polish: one smooth animation to the final total, then mark done
                if (!countAnimDone.isCompleted) {
                    // paint the final number neon green for emphasis
                    vb.totalCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_green_A400))
                    animateCountTo(items.size)
                }
                countAnimDone.await()

                // navigate to results once done
                if (!navigating) {
                    navigating = true
                    vb.cancelButton.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            val current = findNavController().currentDestination?.id
                            if (current == R.id.scanFragment) {
                                val opts = NavOptions.Builder()
                                    .setPopUpTo(R.id.scanFragment, true)
                                    .build()
                                findNavController().navigate(R.id.action_scan_to_results, null, opts)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                if (!isAndroid13Plus()) {
                    showNotSupportedState()
                } else {
                    showErrorState()
                }
            }
        }
    }

    // animates the numeric counter smoothly
    private fun animateCountTo(target: Int) = withVb {
        countAnimator?.cancel()
        val startValue = vb.totalCount.text.toString().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        countAnimator = ValueAnimator.ofInt(startValue, target).apply {
            duration = COUNT_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                vb.totalCount.text = getString(R.string.total_files_count, v)
            }
            doOnEnd {
                lifecycleScope.launch {
                    delay(POST_ANIM_DWELL_MS)
                    if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                }
            }
            start()
        }
    }

    // starts pulsing ring animations
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

    // stops and clears pulse animations
    private fun stopPulses() {
        runningAnims.forEach { it.cancel() }
        runningAnims.clear()
        withVb {
            pulse1.alpha = 0f; pulse1.scaleX = 1f; pulse1.scaleY = 1f
            pulse2.alpha = 0f; pulse2.scaleX = 1f; pulse2.scaleY = 1f
        }
    }

    // cancels scan and returns home
    private fun cancel() {
        job?.cancel()
        stopPulses()
        val nav = findNavController()
        if (nav.currentDestination?.id == R.id.scanFragment) {
            nav.popBackStack(R.id.homeFragment, false)
        }
    }

    // toggles between scanning and state ui
    private fun showScanUI(show: Boolean) {
        vb.scanContent.visibility = if (show) View.VISIBLE else View.GONE
        vb.stateContainer.visibility = if (show) View.GONE else View.VISIBLE
        vb.cancelButton.isEnabled = show && !navigating
    }

    // shown if device version too low
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

    // shown if permission missing
    private fun showPermissionState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.perm_required_title)
        vb.stateMessage.text = getString(R.string.perm_required_msg)
        vb.statePrimary.text = getString(R.string.perm_open_settings)
        vb.statePrimary.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
        }
        vb.stateSecondary.apply {
            visibility = View.VISIBLE
            text = when (selectedType) {
                TypeChoice.PHOTOS -> getString(R.string.perm_request_images)
                TypeChoice.VIDEOS -> getString(R.string.perm_request_videos)
                TypeChoice.AUDIO -> getString(R.string.perm_request_audio)
            }
            setOnClickListener {
                if (isAndroid13Plus()) {
                    requestPerm.launch(requiredPerm(selectedType))
                } else {
                    showNotSupportedState()
                }
            }
        }
    }

    // optional no-media state
    private fun showNoMediaState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.no_media_title)
        vb.stateMessage.text = getString(R.string.no_media_msg)
        vb.statePrimary.text = getString(R.string.go_home)
        vb.statePrimary.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }
        vb.stateSecondary.apply {
            visibility = View.GONE
            setOnClickListener(null)
        }
    }

    // shown on unexpected errors
    private fun showErrorState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.scan_error_title)
        vb.stateMessage.text = getString(R.string.scan_error_msg)
        vb.statePrimary.text = getString(R.string.try_again)
        vb.statePrimary.setOnClickListener {
            val perm = if (isAndroid13Plus()) requiredPerm(selectedType) else null
            if (perm == null || ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
                started = false
                start(selectedType)
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

    override fun onStart() {
        super.onStart()
        withVb {
            if (scanContent.visibility == View.VISIBLE && runningAnims.isEmpty()) {
                startPulses()
            }
        }
    }

    override fun onStop() {
        withVb {
            if (runningAnims.isNotEmpty()) stopPulses()
        }
        super.onStop()
    }

    override fun onDestroyView() {
        job?.cancel()
        stopPulses()
        _vb = null
        super.onDestroyView()
    }

    // safe binding access helper
    private inline fun withVb(block: FragmentScanBinding.() -> Unit) {
        val v = _vb ?: return
        block(v)
    }
}
