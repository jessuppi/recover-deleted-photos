package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
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

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentScanBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vb.cancelButton.setOnClickListener { cancel() }
        start()
    }

    private fun start() {
        // Run on a view-tied scope to avoid leaks if the user navigates away mid-scan.
        job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                // Init progress
                _vb?.progress?.setProgressCompat(0, false)
                _vb?.percent?.text = getString(R.string.percent_format, 0)

                // Do the scan off the main thread; post progress safely.
                val items = withContext(Dispatchers.IO) {
                    MediaScanner(requireContext().applicationContext).scan { pct ->
                        launch(Dispatchers.Main) {
                            _vb?.let {
                                it.progress.setProgressCompat(pct, true)
                                it.percent.text = getString(R.string.percent_format, pct)
                            }
                        }
                    }
                }

                // Store results for the Results screen
                vm.results = items

                // Small delay for UX smoothness
                delay(150)

                // Only navigate if we're still on Scan
                val current = findNavController().currentDestination?.id
                if (isResumed && current == R.id.scanFragment) {
                    // IMPORTANT: Pop Scan from the back stack so Back from Results goes to Home
                    // (prevents the brief "Scanning" flash + auto-restart bouncing back to Results).
                    val opts = NavOptions.Builder()
                        .setPopUpTo(R.id.scanFragment, /*inclusive=*/true)
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
