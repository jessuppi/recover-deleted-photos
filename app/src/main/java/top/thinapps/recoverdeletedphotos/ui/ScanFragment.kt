package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
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
    private var job: Job? = null
    private val vm: ScanViewModel by activityViewModels()

    // real progress from scanner
    @Volatile private var realFound = 0
    @Volatile private var realTotal = 1
    @Volatile private var realUnits = 0   // 0..progressMax

    // throttled ui state (what we show)
    private var displayedUnits = 0
    @Volatile private var scanningDone = false

    // ui throttle settings
    private val progressMax = 1000        // higher granularity = smoother bar, fewer 1px artifacts
    private val uiTickMs = 140L           // update cadence
    private val rateUnitsPerSec = 200f    // lower = slower visual speed

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
                vb.progress.isIndeterminate = false
                vb.progress.max = progressMax
                vb.progress.setProgressCompat(0, false)
                vb.percent.text = getString(R.string.percent_format, 0)
                vb.foundCount.text = getString(R.string.found_count_start)

                // ticker: advance at capped rate, never exceed realUnits
                val ticker = launch(Dispatchers.Main) {
                    var last = SystemClock.uptimeMillis()
                    while (isActive) {
                        val now = SystemClock.uptimeMillis()
                        val dt = (now - last) / 1000f
                        last = now

                        val stepUnits = (rateUnitsPerSec * dt).toInt().coerceAtLeast(1)
                        displayedUnits = minOf(realUnits, displayedUnits + stepUnits)

                        val pct = ((displayedUnits * 100f) / progressMax).toInt().coerceIn(0, 100)
                        vb.progress.setProgressCompat(displayedUnits, true)
                        vb.percent.text = getString(R.string.percent_format, pct)

                        // keep "Found X" in sync with what the user sees
                        val displayedFound = minOf(realFound, (displayedUnits * realTotal) / progressMax)
                        vb.foundCount.text = getString(R.string.found_count, displayedFound)

                        if (scanningDone && displayedUnits >= realUnits) break
                        delay(uiTickMs)
                    }

                    // final clamp to current realUnits
                    val finalPct = ((realUnits * 100f) / progressMax).toInt().coerceIn(0, 100)
                    vb.progress.setProgressCompat(realUnits, true)
                    vb.percent.text = getString(R.string.percent_format, finalPct)
                    val finalFound = minOf(realFound, (realUnits * realTotal) / progressMax)
                    vb.foundCount.text = getString(R.string.found_count, finalFound)
                }

                // run the scan on io; feed real progress
                val items = withContext(Dispatchers.IO) {
                    MediaScanner(requireContext().applicationContext).scan { found, total ->
                        realFound = found
                        realTotal = if (total <= 0) 1 else total
                        realUnits = ((realFound.toLong() * progressMax) / realTotal).toInt().coerceIn(0, progressMax)
                    }
                }

                // store results
                vm.results = items

                // ensure the bar visibly reaches 100% before leaving
                realUnits = progressMax
                scanningDone = true
                ticker.join()

                // snap to 100 without animation to avoid right-edge sliver
                vb.progress.setProgressCompat(progressMax, false)
                vb.percent.text = getString(R.string.percent_format, 100)
                vb.foundCount.text = getString(R.string.found_count, realFound)

                // brief pause at completion
                delay(300)

                // navigate only if still on scan
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

    private fun cancel() {
        job?.cancel()
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroyView() {
        job?.cancel()
        _vb = null
        super.onDestroyView()
    }
}
