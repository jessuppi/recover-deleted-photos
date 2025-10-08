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

    // live progress from scanner
    @Volatile private var realFound = 0
    @Volatile private var realTotal = 1
    @Volatile private var realPercent = 0

    // throttled ui state
    private var displayedPercent = 0
    @Volatile private var scanningDone = false

    // ui throttle settings
    private val uiTickMs = 120L
    private val ratePctPerSec = 70f

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
                vb.progress.setProgressCompat(0, false)
                vb.percent.text = getString(R.string.percent_format, 0)
                vb.foundCount.text = getString(R.string.found_count_start)

                // ui ticker: advance at capped rate, never exceed realPercent
                val ticker = launch(Dispatchers.Main) {
                    var last = SystemClock.uptimeMillis()
                    while (isActive) {
                        val now = SystemClock.uptimeMillis()
                        val dt = (now - last) / 1000f
                        last = now

                        val step = (ratePctPerSec * dt).toInt().coerceAtLeast(1)
                        displayedPercent = minOf(realPercent, displayedPercent + step)

                        vb.progress.setProgressCompat(displayedPercent, true)
                        vb.percent.text = getString(R.string.percent_format, displayedPercent)
                        vb.foundCount.text = getString(R.string.found_count, realFound)

                        if (scanningDone && displayedPercent >= realPercent) break
                        delay(uiTickMs)
                    }

                    // final clamp to real values (may be <100 here; we force 100 after join)
                    vb.progress.setProgressCompat(realPercent, true)
                    vb.percent.text = getString(R.string.percent_format, realPercent)
                    vb.foundCount.text = getString(R.string.found_count, realFound)
                }

                // run the scan on io; feed found/total back to the ui ticker
                val items = withContext(Dispatchers.IO) {
                    MediaScanner(requireContext().applicationContext).scan { found, total ->
                        realFound = found
                        realTotal = if (total <= 0) 1 else total
                        realPercent = ((realFound * 100f) / realTotal).toInt().coerceIn(0, 100)
                    }
                }

                // store results
                vm.results = items

                // ensure the bar visibly reaches 100% before leaving
                realPercent = 100         // force real target to 100
                scanningDone = true
                ticker.join()             // wait for ticker to catch up

                // snap to 100 without animation to avoid a 1px leftover
                vb.progress.setProgressCompat(100, false)
                vb.percent.text = getString(R.string.percent_format, 100)
                vb.foundCount.text = getString(R.string.found_count, realFound)

                // brief pause so users see completion
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
