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
import kotlinx.coroutines.isActive
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
        private const val TICK_MS = 50L
    }

    // coroutine jobs
    private var job: Job? = null
    private var tickerJob: Job? = null
    private var latestTotal: Int = 0

    enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // read scan type from nav args
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
        // always clear results when opening this screen
        vm.results = emptyList()
        vb.cancelButton.setOnClickListener { cancel() }

        // android 13+ required
        if (!isAndroid13Plus()) {
            showNotSupportedState()
            return
        }

        // check permission and start scan if granted
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

    // tracks completion of dwell after final count animation
    private var countAnimDone = CompletableDeferred<Unit>()

    // starts scanning flow
    private fun start(type: TypeChoice) = withVb {
        showScanUI(true)

        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // confirm permission before scanning
                if (!hasPermission(type)) {
                    showPermissionState()
                    stopPulses()
                    return@launch
                }

                // reset ui state for new scan
                vb.totalLabel.text = when (type) {
                    TypeChoice.PHOTOS -> getString(R.string.total_photos_label)
                    TypeChoice.VIDEOS -> getString(R.string.total_videos_label)
                    TypeChoice.AUDIO -> getString(R.string.total_audio_label)
                }
                vb.totalCount.text = getString(R.string.total_files_count, 0)
                vb.totalCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_surface))
                latestTotal = 0

                // start animations and ticker
                countAnimDone = CompletableDeferred()
                startPulses()
                startCountTicker()

                // run mediascanner in background
                val items = runCatching {
                    withContext(Dispatchers.IO) {
                        var lastEmitted = 0
                        var lastUiPost = 0L
                        MediaScanner(requireContext().applicationContext).scan(
                            includeImages = type == TypeChoice.PHOTOS,
                            includeVideos = type == TypeChoice.VIDEOS,
                            includeAudio = type == TypeChoice.AUDIO
                        ) { _, total ->
                            val now = SystemClock.uptimeMillis()
                            if (total != lastEmitted && now - lastUiPost >= 150L) {
                                lastEmitted = total
                                lastUiPost = now
                                vb.root.post { latestTotal = total }
                            }
                        }
                    }
                }.getOrElse {
                    if (it is SecurityException) {
                        showPermissionState()
                    } else {
                        showErrorState()
                    }
                    stopCountTicker()
                    stopPulses()
                    return@launch
                }

                // stop ticker and finish final count
                stopCountTicker()
                val finalTotal = items.size
                if (!countAnimDone.isCompleted) animateCountTo(finalTotal)
                countAnimDone.await()

                // show empty state if nothing found
                if (items.isEmpty()) {
                    stopPulses()
                    showNoMediaState()
                    return@launch
                }

                // publish new results to shared viewmodel
                vm.results = items

                // navigate to results safely when ready
                if (!navigating) {
                    navigating = true
                    vb.cancelButton.isEnabled = false
                    navigateToResultsSafely()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                stopCountTicker()
                if (!isAndroid13Plus()) showNotSupportedState() else showErrorState()
            }
        }
    }

    // safe navigation helper (waits until fragment is resumed)
    private fun navigateToResultsSafely() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.scanFragment) {
                    val opts = NavOptions.Builder()
                        .setPopUpTo(R.id.scanFragment, true)
                        .build()
                    nav.navigate(R.id.action_scan_to_results, null, opts)
                }
                return@repeatOnLifecycle
            }
        }
    }

    // ticker slowly increases visible count during scan
    private fun startCountTicker() {
        stopCountTicker()
        tickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val current = vb.totalCount.text.toString().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                val target = latestTotal
                if (current < target) {
                    val diff = target - current
                    val step = when {
                        diff > 1000 -> 50
                        diff > 400 -> 25
                        diff > 150 -> 10
                        diff > 30 -> 5
                        else -> 1
                    }
                    val next = (current + step).coerceAtMost(target)
                    vb.totalCount.text = getString(R.string.total_files_count, next)
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopCountTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // animates counter to final total and turns green briefly (with soft glow during dwell)
    private fun animateCountTo(target: Int) = withVb {
        countAnimator?.cancel()
        val startValue = vb.totalCount.text.toString().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        countAnimator = ValueAnimator.ofInt(startValue, target).apply {
            duration = COUNT_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                vb.totalCount.text = getString(R.string.total_files_count, anim.animatedValue as Int)
            }
            doOnEnd {
                vb.totalCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_green_A400))
                // green glow dwell
                vb.totalCount.setShadowLayer(8f, 0f, 0f, ContextCompat.getColor(requireContext(), R.color.md_green_A200))
                lifecycleScope.launch {
                    delay(POST_ANIM_DWELL_MS)
                    vb.totalCount.setShadowLayer(0f, 0f, 0f, 0)
                    if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                }
            }
            start()
        }
    }

    // starts pulsing ring effect (now with gradient background)
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
            // gradient ring background + softer alpha pulse
            view.background = ContextCompat.getDrawable(requireContext(), R.drawable.pulse_gradient)
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 0.25f, 0f).apply {
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

    // stops pulse animations
    private fun stopPulses() {
        runningAnims.forEach { it.cancel() }
        runningAnims.clear()
        withVb {
            pulse1.alpha = 0f; pulse1.scaleX = 1f; pulse1.scaleY = 1f
            pulse2.alpha = 0f; pulse2.scaleX = 1f; pulse2.scaleY = 1f
        }
    }

    // cancels scan and returns to home (no minimize/fall-through)
    private fun cancel() {
        job?.cancel()
        stopCountTicker()
        stopPulses()
        vm.results = emptyList()

        val nav = findNavController()
        // try to pop back to home if it's on the stack
        val popped = nav.popBackStack(R.id.homeFragment, false)
        if (!popped) {
            // fallback: navigate to home explicitly and clear anything above the start destination
            val opts = NavOptions.Builder()
                .setPopUpTo(nav.graph.startDestinationId, /* inclusive = */ true)
                .build()
            nav.navigate(R.id.homeFragment, null, opts)
        }
    }

    // toggles between scan ui and state screen (+ breathing cancel button when scanning)
    private fun showScanUI(show: Boolean) {
        vb.scanContent.visibility = if (show) View.VISIBLE else View.GONE
        vb.stateContainer.visibility = if (show) View.GONE else View.VISIBLE
        vb.cancelButton.isEnabled = show && !navigating

        if (show) {
            ObjectAnimator.ofFloat(vb.cancelButton, View.ALPHA, 0.8f, 1f).apply {
                duration = 1200L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        } else {
            vb.cancelButton.alpha = 1f
        }
    }

    // state: android version too low
    private fun showNotSupportedState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.android_13_required_title)
        vb.stateMessage.text = getString(R.string.android_13_required_msg)
        vb.statePrimary.text = getString(R.string.go_home)
        vb.statePrimary.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }
        vb.stateSecondary.visibility = View.GONE
    }

    // state: missing permission
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
        vb.stateSecondary.visibility = View.VISIBLE
        vb.stateSecondary.text = when (selectedType) {
            TypeChoice.PHOTOS -> getString(R.string.perm_request_images)
            TypeChoice.VIDEOS -> getString(R.string.perm_request_videos)
            TypeChoice.AUDIO -> getString(R.string.perm_request_audio)
        }
        vb.stateSecondary.setOnClickListener {
            if (isAndroid13Plus()) requestPerm.launch(requiredPerm(selectedType))
            else showNotSupportedState()
        }
    }

    // state: no media found
    private fun showNoMediaState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.no_media_title)
        vb.stateMessage.text = getString(R.string.no_media_msg)
        vb.statePrimary.text = getString(R.string.go_home)
        vb.statePrimary.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }
        vb.stateSecondary.visibility = View.GONE
    }

    // state: general scan error
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
        vb.stateSecondary.text = getString(R.string.go_home)
        vb.stateSecondary.visibility = View.VISIBLE
        vb.stateSecondary.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }
    }

    override fun onStart() {
        super.onStart()
        withVb {
            if (scanContent.visibility == View.VISIBLE && runningAnims.isEmpty()) startPulses()
        }
    }

    override fun onStop() {
        withVb { if (runningAnims.isNotEmpty()) stopPulses() }
        super.onStop()
    }

    override fun onDestroyView() {
        job?.cancel()
        stopCountTicker()
        stopPulses()
        _vb = null
        super.onDestroyView()
    }

    // safe binding access
    private inline fun withVb(block: FragmentScanBinding.() -> Unit) {
        val v = _vb ?: return
        block(v)
    }
}
