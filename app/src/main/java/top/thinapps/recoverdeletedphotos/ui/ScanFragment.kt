package top.thinapps.recoverdeletedphotos.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.animation.doOnEnd
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

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentScanBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vb.cancelButton.setOnClickListener { cancel() }
        start()
    }

    private fun start() {
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
                    MediaScanner(requireContext().applicationContext).scan { _, total ->
                        if (totalSeen == 0 && total > 0) {
                            totalSeen = total
                            // launch animator on main, bound to lifecycle
                            viewLifecycleOwner.lifecycleScope.launch {
                                animateCountTo(totalSeen)
                            }
                        }
                    }
                }

                // pass results to vm for next screen
                vm.results = items

                // wait for count animation + dwell before navigating
                countAnimDone.await()

                // navigate if still on this fragment
                val current = findNavController().currentDestination?.id
                if (isResumed && current == R.id.scanFragment) {
                    val opts = NavOptions.Builder()
                        .setPopUpTo(R.id.scanFragment, true)
                        .build()
                    findNavController().navigate(R.id.action_scan_to_results, null, opts)
                }
            } catch (t: Throwable) {
                // ignore user-initiated cancel
                if (t is CancellationException) return@launch
                if (isAdded) {
                    Toast.makeText(requireContext(), "scan failed", Toast.LENGTH_SHORT).show()
                    val nav = findNavController()
                    if (!nav.popBackStack(R.id.homeFragment, false)) {
                        nav.navigate(
                            R.id.homeFragment,
                            null,
                            NavOptions.Builder().setPopUpTo(R.id.homeFragment, false).build()
                        )
                    }
                }
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
        if (!nav.popBackStack(R.id.homeFragment, false)) {
            nav.navigate(
                R.id.homeFragment,
                null,
                NavOptions.Builder().setPopUpTo(R.id.homeFragment, false).build()
            )
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        stopPulses()
        _vb = null
        super.onDestroyView()
    }
}
