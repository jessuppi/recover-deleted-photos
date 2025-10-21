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
import androidx.activity.OnBackPressedCallback
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
import top.thinapps.recoverdeletedphotos.MainActivity
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentScanBinding
import top.thinapps.recoverdeletedphotos.scan.MediaScanner

class ScanFragment : Fragment() {

    // viewbinding (cleared in ondestroyview to avoid leaks)
    private var _vb: FragmentScanBinding? = null
    private val vb get() = _vb!!

    // shared viewmodel used to hand results to resultsfragment
    private val vm: ScanViewModel by activityViewModels()

    // ---- state flags ---------------------------------------------------------

    private var navigating = false   // prevents double-navigation
    private var started = false      // ensures we start scan only once
    private var canceled = false     // stops work & navigation after cancel
    private var selectedType: TypeChoice = TypeChoice.PHOTOS

    // ---- animation state -----------------------------------------------------

    // scale animators for the two pulse views
    private val runningAnims = mutableListOf<ObjectAnimator>()
    // alpha animators that fade only the drawable (prevents square boxing)
    private val pulseAlphaAnimators = mutableListOf<ValueAnimator>()
    private var countAnimator: ValueAnimator? = null           // final count animator

    // ---- timing constants ----------------------------------------------------

    private companion object {
        private const val COUNT_ANIM_MS = 3800L      // duration for final count-up
        private const val POST_ANIM_DWELL_MS = 1200L // (unused now for glow; kept for future tweaks)
        private const val PULSE_CYCLE_MS = 2800L     // pulse ring cycle time
        private const val TICK_MS = 50L              // ticker cadence
    }

    // ---- coroutines ----------------------------------------------------------

    private var job: Job? = null           // main scanning job
    private var tickerJob: Job? = null     // smooth ticker job
    private var latestTotal: Int = 0       // live target for ticker to approach

    enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    // read which media type to scan from the nav args
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedType = when (arguments?.getString("type")) {
            "VIDEOS" -> TypeChoice.VIDEOS
            "AUDIO" -> TypeChoice.AUDIO
            else -> TypeChoice.PHOTOS
        }
    }

    // runtime permission launcher (android 13+)
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

    // inflate binding
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentScanBinding.inflate(inflater, c, false)
        return vb.root
    }

    // initial ui setup & permission gate
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // always clear any previous results to avoid caching between runs
        vm.results = emptyList()

        // cancel button stops work and returns to home safely
        vb.cancelButton.setOnClickListener { cancel() }

        // make system back behave like cancel (stops jobs, clears results, goes home safely)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    cancel()
                }
            }
        )

        // set toolbar title and visibility via mainactivity
        (requireActivity() as? MainActivity)?.apply {
            // show toolbar for scan screen
            setToolbarVisible(true)
            // set proper localized title
            setToolbarTitle(getString(R.string.scan_title))
        }

        // feature is android 13+ only
        if (!isAndroid13Plus()) {
            showNotSupportedState()
            return
        }

        // check permission and kick off scan
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

    // ---- permission helpers --------------------------------------------------

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

    // completed when final count animation + pulse completes
    private var countAnimDone = CompletableDeferred<Unit>()

    // ---- scanning flow -------------------------------------------------------

    private fun start(type: TypeChoice) = withVb {
        // show scanning ui and hide state screens
        showScanUI(true)

        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // double-check permission right before scanning
                if (!hasPermission(type)) {
                    showPermissionState()
                    stopPulses()
                    return@launch
                }

                // reset labels and counter at the beginning of each scan
                vb.totalLabel.text = when (type) {
                    TypeChoice.PHOTOS -> getString(R.string.total_photos_label)
                    TypeChoice.VIDEOS -> getString(R.string.total_videos_label)
                    TypeChoice.AUDIO -> getString(R.string.total_audio_label)
                }
                vb.totalCount.text = getString(R.string.total_files_count, 0)
                vb.totalCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_surface))
                latestTotal = 0

                // kick off visuals: soft transparent pulses + ticker that climbs toward live total
                countAnimDone = CompletableDeferred()
                startPulses()
                startCountTicker()

                // run the mediascanner off the main thread and feed totals back periodically
                val items = runCatching {
                    withContext(Dispatchers.IO) {
                        var lastEmitted = 0
                        var lastUiPost = 0L
                        MediaScanner(requireContext().applicationContext).scan(
                            includeImages = type == TypeChoice.PHOTOS,
                            includeVideos = type == TypeChoice.VIDEOS,
                            includeAudio = type == TypeChoice.AUDIO
                        ) { _, total ->
                            // throttle ui updates — the ticker makes it feel smooth
                            val now = SystemClock.uptimeMillis()
                            if (total != lastEmitted && now - lastUiPost >= 150L) {
                                lastEmitted = total
                                lastUiPost = now
                                vb.root.post { latestTotal = total }
                            }
                        }
                    }
                }.getOrElse {
                    // handle both securityexception (perm revoked) and general errors
                    if (it is SecurityException) {
                        showPermissionState()
                    } else {
                        showErrorState()
                    }
                    stopCountTicker()
                    stopPulses()
                    return@launch
                }

                // finish ticker and do a single, nice final animation to the exact total
                stopCountTicker()
                val finalTotal = items.size
                if (!countAnimDone.isCompleted) animateCountTo(finalTotal)
                countAnimDone.await()

                // if nothing found, show the "no media" state and do not navigate
                if (items.isEmpty()) {
                    stopPulses()
                    showNoMediaState()
                    return@launch
                }

                // publish results to the shared viewmodel (consumed by resultsfragment)
                vm.results = items

                // navigate out only once, and only when we're in a safe lifecycle state
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

    // wait until resumed and then navigate, with guards to avoid crashes after cancel/back
    private fun navigateToResultsSafely() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (canceled) return@repeatOnLifecycle
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.scanFragment) {
                    val opts = NavOptions.Builder()
                        .setPopUpTo(R.id.scanFragment, true)
                        .build()
                    runCatching { nav.navigate(R.id.action_scan_to_results, null, opts) }
                }
                return@repeatOnLifecycle
            }
        }
    }

    // ---- counter/ticker visuals ---------------------------------------------

    // smooth ticker that climbs toward latesttotal; keeps the count "alive" during the scan
    private fun startCountTicker() {
        stopCountTicker()
        tickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val current = vb.totalCount.text.toString().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                val target = latestTotal
                if (current < target) {
                    val diff = target - current
                    // adaptive step so it feels deliberate, not jumpy
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

    // final animation: count to the exact total, then do a subtle scale pulse (no glow)
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
                // subtle emphasis: scale up then back down (keeps text color unchanged)
                val interp = FastOutSlowInInterpolator()
                vb.totalCount.animate()
                    .scaleX(1.12f).scaleY(1.12f)
                    .setDuration(180L)
                    .setInterpolator(interp)
                    .withEndAction {
                        vb.totalCount.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(180L)
                            .setInterpolator(interp)
                            .withEndAction {
                                if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                            }
                            .start()
                    }
                    .start()
            }
            start()
        }
    }

    // ---- pulse ring visuals --------------------------------------------------

    // soft transparent pulses: scale the view, fade only the drawable (not view.alpha)
    private fun startPulses() { /* unchanged from original */ }

    // stops and clears all running pulse animations and resets views
    private fun stopPulses() { /* unchanged from original */ }

    // ---- cancel handling -----------------------------------------------------

    // cancel stops scanning/animations, clears results, and returns to home without minimizing/crashing
    private fun cancel() { /* unchanged from original */ }

    // ---- state screens -------------------------------------------------------

    // toggle between the scanning ui and the generic state container.
    // when scanning, add a subtle breathing animation to the cancel button.
    private fun showScanUI(show: Boolean) { /* unchanged from original */ }

    // device not supported (sdk < 33)
    private fun showNotSupportedState() { /* unchanged from original */ }

    // permission needed to proceed
    private fun showPermissionState() { /* unchanged from original */ }

    // no media found for the chosen type
    private fun showNoMediaState() { /* unchanged from original */ }

    // unexpected error during scan
    private fun showErrorState() { /* unchanged from original */ }

    // ---- lifecycle glue ------------------------------------------------------

    override fun onStart() {
        super.onStart()
        withVb {
            // if we come back and the scan ui is visible, ensure pulses are running
            if (scanContent.visibility == View.VISIBLE && runningAnims.isEmpty() && pulseAlphaAnimators.isEmpty()) {
                startPulses()
            }
        }
    }

    override fun onStop() {
        // stop pulses when we leave the foreground to save work/battery
        withVb { if (runningAnims.isNotEmpty() || pulseAlphaAnimators.isNotEmpty()) stopPulses() }
        super.onStop()
    }

    override fun onDestroyView() {
        // clean up to avoid leaks and lingering jobs
        job?.cancel()
        stopCountTicker()
        stopPulses()
        _vb = null
        super.onDestroyView()
    }

    // safe binding accessor (no-ops if view is already destroyed)
    private inline fun withVb(block: FragmentScanBinding.() -> Unit) {
        val v = _vb ?: return
        block(v)
    }
}
