package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _vb: FragmentHomeBinding? = null
    private val vb get() = _vb!!

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.all { it }
        if (ok) {
            findNavController().navigate(R.id.action_home_to_scan)
        } else {
            Snackbar.make(vb.root, R.string.perms_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentHomeBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vb.startButton.setOnClickListener {
            maybeAskForPermissionsOrContinue()
        }
        vb.subtitle.isVisible = true
    }

    private fun maybeAskForPermissionsOrContinue() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPerms.launch(arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            ))
        } else {
            requestPerms.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }
}
