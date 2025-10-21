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
import top.thinapps.recoverdeletedphotos.MainActivity

private const val ARG_TYPE = "type"

class HomeFragment : Fragment() {

    private var _vb: FragmentHomeBinding? = null
    private val vb get() = _vb!!

    private enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    // stores selected type across permission request flow
    private var pendingType: TypeChoice? = null

    // handles runtime permission for a single media type
    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // safely exit if view is already destroyed
        val root = _vb?.root ?: return@registerForActivityResult
        if (!isAdded) return@registerForActivityResult

        // read stored type and clear state
        val type = pendingType
        pendingType = null

        // open scan screen or show denied message
        if (granted && type != null) {
            navigateToScan(type)
        } else {
            Snackbar.make(root, R.string.perms_denied, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_settings) {
                    // open system settings for manual permission grant
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", requireContext().packageName, null)
                    )
                    startActivity(intent)
                }
                .show()
        }

        // re-enable button regardless of outcome
        vb.startButton.isEnabled = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        c: ViewGroup?,
        s: Bundle?
    ): View {
        // inflate layout for this fragment
        _vb = FragmentHomeBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ensure toolbar visible and reset title for home screen
        (activity as? MainActivity)?.setToolbarVisible(true)
        (activity as? MainActivity)?.setToolbarTitle(getString(R.string.app_name))

        // show subtitle text on home screen
        vb.subtitle.isVisible = true

        // main action button for starting scan
        vb.startButton.setOnClickListener {
            vb.startButton.isEnabled = false
            val type = currentType()
            val perm = requiredPerm(type)

            // skip permission if already granted
            if (hasPermission(perm)) {
                navigateToScan(type)
                vb.startButton.isEnabled = true
            } else {
                // remember chosen type for callback
                pendingType = type
                requestPerm.launch(perm)
            }
        }
    }

    // returns user-selected media type
    private fun currentType(): TypeChoice = when {
        vb.homeTypeVideos?.isChecked == true -> TypeChoice.VIDEOS
        vb.homeTypeAudio?.isChecked == true -> TypeChoice.AUDIO
        else -> TypeChoice.PHOTOS
    }

    // returns permission required for given media type
    private fun requiredPerm(type: TypeChoice): String {
        return if (Build.VERSION.SDK_INT < 33) {
            Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            when (type) {
                TypeChoice.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
                TypeChoice.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
                TypeChoice.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            }
        }
    }

    // checks whether a permission is already granted
    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    // opens scan fragment with selected type argument
    private fun navigateToScan(type: TypeChoice) {
        findNavController().navigate(
            R.id.action_home_to_scan,
            bundleOf(ARG_TYPE to type.name)
        )
    }

    override fun onDestroyView() {
        // clear references and pending state to avoid leaks
        pendingType = null
        _vb = null
        super.onDestroyView()
    }
}
