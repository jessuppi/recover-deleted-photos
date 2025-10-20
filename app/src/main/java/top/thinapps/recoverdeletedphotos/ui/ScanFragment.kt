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
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.Toolbar
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

    private val runningAnims = mutableListOf<ObjectAnimator>() // pulse anims to stop on lifecycle changes
    private var countAnimator: ValueAnimator? = null           // final count animator

    // ---- timing constants ----------------------------------------------------

    private companion object {
        private const val COUNT_ANIM_MS = 3800L      // duration for final count-up
        private const val POST_ANIM_DWELL_MS = 1200L // dwell on final green number
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

        // wire toolbar up arrow to the same safe cancel path
        setHasOptionsMenu(true)
        vb.root.findViewById<Toolbar?>(R.id.toolbar)?.setNavigationOnClickListener { cancel() }
        requireActivity().findViewById<Toolbar?>(R.id.toolbar)?.setNavigationOnClickListener { cancel() }

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

    // intercept actionbar home as a final safety net
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            cancel()
            true
        } else {
            super.onOptionsItemSelected(item)
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

    // completed when final count animation + dwell finish
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

                // kick off visuals: pulsing rings + soft ticker that climbs toward the live total
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

    // final animation: count to the exact total, then turn neon green and dwell briefly with a glow
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
                // turn green immediately at final value and add a soft glow for the dwell
                val green = ContextCompat.getColor(requireContext(), R.color.md_green_A400)
                vb.totalCount.setTextColor(green)
                vb.totalCount.setShadowLayer(8f, 0f, 0f, green)
                lifecycleScope.launch {
                    delay(POST_ANIM_DWELL_MS)
                    vb.totalCount.setShadowLayer(0f, 0f, 0f, 0)
                    if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                }
            }
            start()
        }
    }

    // ---- pulse ring visuals --------------------------------------------------

    // starts two offset pulse rings behind the counter; uses gradient background
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
            // gradient ring and softer alpha breathing
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

    // stops and clears all running pulse animations and resets views
    private fun stopPulses() {
        runningAnims.forEach { it.cancel() }
        runningAnims.clear()
        withVb {
            pulse1.alpha = 0f; pulse1.scaleX = 1f; pulse1.scaleY = 1f
            pulse2.alpha = 0f; pulse2.scaleX = 1f; pulse2.scaleY = 1f
        }
    }

    // ---- cancel handling -----------------------------------------------------

    // cancel stops scanning/animations, clears results, and returns to home without minimizing/crashing
    private fun cancel() {
        if (canceled) return
        canceled = true
        navigating = true
        vb.cancelButton.isEnabled = false

        // stop all work/animations immediately
        job?.cancel()
        stopCountTicker()
        countAnimator?.cancel()
        stopPulses()

        // clear any in-memory results for privacy/freshness
        vm.results = emptyList()

        val nav = runCatching { findNavController() }.getOrNull() ?: return

        // wait for resumed before touching nav to avoid illegalstateexception
        viewLifecycleOwner.lifecycleScope.launch {
            while (lifecycle.currentState < Lifecycle.State.RESUMED) {
                delay(16)
            }
            runCatching {
                // prefer popping back to an existing home
                val popped = nav.popBackStack(R.id.homeFragment, false)
                if (!popped) {
                    // fallback: navigate explicitly to home (or start destination)
                    val homeId = if (nav.graph.findNode(R.id.homeFragment) != null)
                        R.id.homeFragment
                    else
                        nav.graph.startDestinationId

                    val opts = NavOptions.Builder()
                        .setPopUpTo(homeId, false)
                        .build()
                    nav.navigate(homeId, null, opts)
                }
            }
        }
    }

    // ---- state screens -------------------------------------------------------

    // toggle between the scanning ui and the generic state container.
    // when scanning, add a subtle breathing animation to the cancel button.
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

    // device not supported (sdk < 33)
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

    // permission needed to proceed
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

    // no media found for the chosen type
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

    // unexpected error during scan
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

    // ---- lifecycle glue ------------------------------------------------------

    override fun onStart() {
        super.onStart()
        withVb {
            // if we come back and the scan ui is visible, ensure pulses are running
            if (scanContent.visibility == View.VISIBLE && runningAnims.isEmpty()) startPulses()
        }
    }

    override fun onStop() {
        // stop pulses when we leave the foreground to save work/battery
        withVb { if (runningAnims.isNotEmpty()) stopPulses() }
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
