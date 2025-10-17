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

    // permission launcher for selected type
    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // stop if view is gone or fragment detached
        val root = _vb?.root ?: return@registerForActivityResult
        if (!isAdded) return@registerForActivityResult

        // check granted permissions
        val type = currentType()
        val wanted = requiredPerms(type)
        val ok = wanted.all { granted[it] == true }

        // go to scan if ok or show snackbar
        if (ok) navigateToScan(type)
        else Snackbar.make(root, R.string.perms_denied, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        // inflate layout
        _vb = FragmentHomeBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ensure subtitle visible
        vb.subtitle.isVisible = true

        // start button logic
        vb.startButton.setOnClickListener {
            val type = currentType()
            if (hasPermission(type)) navigateToScan(type)
            else requestPerms.launch(requiredPerms(type))
        }
    }

    // determine which type is selected
    private fun currentType(): TypeChoice = when {
        vb.homeTypeVideos?.isChecked == true -> TypeChoice.VIDEOS
        vb.homeTypeAudio?.isChecked == true  -> TypeChoice.AUDIO
        else                                 -> TypeChoice.PHOTOS
    }

    // return needed permissions per type
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

    // check if required permissions granted
    private fun hasPermission(type: TypeChoice): Boolean {
        val perms = requiredPerms(type)
        return perms.all { p ->
            ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED
        }
    }

    // navigate to scan fragment with type arg
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
        // clear binding reference
        _vb = null
        super.onDestroyView()
    }
}
