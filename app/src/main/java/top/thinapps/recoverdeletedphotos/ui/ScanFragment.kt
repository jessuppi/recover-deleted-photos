package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentScanBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import top.thinapps.recoverdeletedphotos.scan.MediaScanner

class ScanFragment : Fragment() {
    private var _vb: FragmentScanBinding? = null
    private val vb get() = _vb!!
    private var job: Job? = null

    // shared viewmodel for storing scan results
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
        job = viewLifecycleOwner.lifecycleScope.launch {
            vb.progress.setProgressCompat(0, false)
            val items = MediaScanner(requireContext()).scan { pct ->
                vb.progress.setProgressCompat(pct, true)
                vb.percent.text = getString(R.string.percent_format, pct)
            }

            // store results in shared viewmodel
            vm.results = items

            // small delay for ux smoothness
            delay(250)
            findNavController().navigate(R.id.action_scan_to_results)
        }
    }

    private fun cancel() {
        job?.cancel()
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }
}
