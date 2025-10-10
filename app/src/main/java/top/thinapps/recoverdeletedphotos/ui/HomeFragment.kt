package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _vb: FragmentHomeBinding? = null
    private val vb get() = _vb!!

    private enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    // Permission launcher (requests are built from the selected type)
    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val type = currentType()
        val wanted = requiredPerms(type)
        val ok = wanted.all { granted[it] == true }
        if (ok) {
            navigateToScan(type)
        } else {
            Snackbar.make(vb.root, R.string.perms_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentHomeBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // default Photos is checked in XML
        vb.subtitle.isVisible = true

        vb.startButton.setOnClickListener {
            val type = currentType()
            if (hasPermission(type)) {
                navigateToScan(type)
            } else {
                requestPerms.launch(requiredPerms(type))
            }
        }
    }

    private fun currentType(): TypeChoice = when {
        vb.homeTypeVideos?.isChecked == true -> TypeChoice.VIDEOS
        vb.homeTypeAudio?.isChecked == true  -> TypeChoice.AUDIO
        else                                 -> TypeChoice.PHOTOS
    }

    private fun requiredPerms(type: TypeChoice): Array<String> {
        return if (Build.VERSION.SDK_INT < 33) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            when (type) {
                TypeChoice.PHOTOS -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                TypeChoice.VIDEOS -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                TypeChoice.AUDIO  -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
    }

    private fun hasPermission(type: TypeChoice): Boolean {
        val perms = requiredPerms(type)
        return perms.all { p ->
            ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun navigateToScan(type: TypeChoice) {
        val typeArg = when (type) {
            TypeChoice.PHOTOS -> "PHOTOS"
            TypeChoice.VIDEOS -> "VIDEOS"
            TypeChoice.AUDIO  -> "AUDIO"
        }
        findNavController().navigate(
            R.id.action_home_to_scan,
            bundleOf("type" to typeArg)
        )
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }
}
