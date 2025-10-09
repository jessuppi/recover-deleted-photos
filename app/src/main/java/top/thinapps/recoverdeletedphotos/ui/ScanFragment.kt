package top.thinapps.recoverdeletedphotos.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
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

    private val runningAnims = mutableListOf<ObjectAnimator>()
    private var countAnimator: ValueAnimator? = null

    // pacing
    private val COUNT_ANIM_MS = 3800L      // slower number ramp
    private val POST_ANIM_DWELL_MS = 1200L // linger after finishing
    private val PULSE_CYCLE_MS = 2800L     // slower halo

    // signal when the count animation fully completes
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
        job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                // init ui
                vb.totalLabel.text = getString(R.string.total_files_label)
                vb.totalCount.text = getString(R.string.total_files_count, 0)
                countAnimDone = CompletableDeferred()

                // start pulse halo
                startPulses()

                // scan and grab the true total; animate the number once to that total
                val items = withContext(Dispatchers.IO) {
                    var totalSeen = 0
                    MediaScanner(requireContext().applicationContext).scan { _, total ->
                        if (totalSeen == 0 && total > 0) {
                            totalSeen = total
                            // animate on MAIN, bound to the view lifecycle
                            viewLifecycleOwner.lifecycleScope.launch {
                                animateCountTo(totalSeen)
                            }
                        }
                    }
                }

                vm.results = items

                // wait for the count animation + dwell before navigating
                countAnimDone.await()

                val current = findNavController().currentDestination?.id
                if (isResumed && current == R.id.scanFragment) {
                    val opts = NavOptions.Builder()
                        .setPopUpTo(R.id.scanFragment, true)
                        .build()
                    findNavController().navigate(R.id.action_scan_to_results, null, opts)
                }
            } catch (t: Throwable) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "scan failed", Toast.LENGTH_SHORT).show()
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private suspend fun animateCountTo(target: Int) {
        // cancel any previous animator to avoid overlap
        countAnimator?.cancel()

        // smooth ease-out using FastOutSlowIn
        countAnimator = ValueAnimator.ofInt(0, target).apply {
            duration = COUNT_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Int
                vb.totalCount.text = getString(R.string.total_files_count, v)
            }
            addListener(onEnd = {
                // hold briefly so users register completion
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(POST_ANIM_DWELL_MS)
                    if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                }
            })
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
        pulse(vb.pulse2, PULSE_CYCLE_MS / 2) // staggered for a continuous wave
    }

    private fun stopPulses() {
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
        job?.cancel()
        stopPulses()
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroyView() {
        job?.cancel()
        stopPulses()
        _vb = null
        super.onDestroyView()
    }
}
