package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
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
        // run on lifecycle scope tied to view to avoid leaks
        job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                // init progress
                _vb?.progress?.setProgressCompat(0, false)
                _vb?.percent?.text = getString(R.string.percent_format, 0)

                // do scan work off the main thread and post progress safely
                val items = withContext(Dispatchers.IO) {
                    MediaScanner(requireContext().applicationContext).scan { pct ->
                        // post progress updates to main only if view still alive
                        launch(Dispatchers.Main) {
                            _vb?.let {
                                it.progress.setProgressCompat(pct, true)
                                it.percent.text = getString(R.string.percent_format, pct)
                            }
                        }
                    }
                }

                // store results for next screen
                vm.results = items

                // small delay for ux smoothness
                delay(150)

                // only navigate if this fragment is still current and resumed
                val current = findNavController().currentDestination?.id
                if (isResumed && current == R.id.scanFragment) {
                    findNavController().navigate(R.id.action_scan_to_results)

                    // clear results immediately after navigation to avoid auto return to results and protect privacy
                    vm.results = emptyList()
                }
            } catch (t: Throwable) {
                // show a simple message and go back instead of crashing
                if (isAdded) {
                    Toast.makeText(requireContext(), "scan failed", Toast.LENGTH_SHORT).show()
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private fun cancel() {
        job?.cancel()
        // clear any partial results to avoid leaking scanned data if canceled
        vm.results = emptyList()
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }
}
