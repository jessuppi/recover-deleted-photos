package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES.TIRAMISU
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
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
        if (!isAdded) return@registerForActivityResult

        // read stored type and clear state
        val type = pendingType
        pendingType = null
        
        vb.startButton.isEnabled = true // Re-enable button after request attempt finishes

        // open scan screen or update button to suggest opening settings
        if (granted && type != null) {
            navigateToScan(type)
        } else {
            // Permission denied -> update button state to prompt for manual settings change (No Snackbar)
            updateButtonText()
        }
    }

    // checks for Android 13+ support
    private fun isAndroid13Plus(): Boolean =
        Build.VERSION.SDK_INT >= TIRAMISU

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

        // Check for required API level before enabling functionality
        if (!isAndroid13Plus()) {
            vb.startButton.isEnabled = false
            vb.homeTypeRow.isVisible = false
            vb.stateMessage.text = getString(R.string.android_13_required_msg)
            vb.stateMessage.isVisible = true
            return
        }
        
        // Set initial button text based on current permission status
        updateButtonText()

        // Listener to re-update button text when user changes the media type filter
        vb.homeTypeRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateButtonText()
        }

        // main action button for starting scan/settings
        vb.startButton.setOnClickListener {
            vb.startButton.isEnabled = false
            val type = currentType()
            val perm = requiredPerm(type)
            val buttonText = vb.startButton.text

            if (hasPermission(perm)) {
                // Flow 1: Permission is granted -> Start Scan
                navigateToScan(type)
                vb.startButton.isEnabled = true
            } else if (buttonText == getString(R.string.action_grant_settings)) {
                // Flow 2: Permission is denied and button says "Grant Access" -> Open Settings
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
                startActivity(intent)
                vb.startButton.isEnabled = true // Re-enable as user is leaving the app
            } else {
                // Flow 3: Permission is NOT granted and button says "Start Scan" -> Request permission
                pendingType = type
                requestPerm.launch(perm)
            }
        }
    }

    // NEW HELPER: Sets the button text based on the current permission state for the selected type.
    private fun updateButtonText() {
        val perm = requiredPerm(currentType())
        if (hasPermission(perm)) {
            vb.startButton.text = getString(R.string.start_scan)
        } else {
            // If permission is denied, change the button to prompt opening settings
            vb.startButton.text = getString(R.string.action_grant_settings)
        }
        vb.startButton.isEnabled = true // The button should always be clickable here
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
