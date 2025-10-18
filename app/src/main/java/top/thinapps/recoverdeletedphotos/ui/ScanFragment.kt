package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentScanBinding
import top.thinapps.recoverdeletedphotos.scan.MediaScanner

class ScanFragment : Fragment() {

    private enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    private var _vb: FragmentScanBinding? = null
    private val vb get() = _vb!!
    // helper to safely use binding from async callbacks/animators
    private inline fun withVb(block: FragmentScanBinding.() -> Unit) { _vb?.let(block) }

    private var job: Job? = null
    private val vm: ScanViewModel by activityViewModels()

    // animations
    private val runningAnims = mutableListOf<ObjectAnimator>()
    private var countAnimator: ValueAnimator? = null

    // timing (ms)
    private companion object {
        private const val COUNT_ANIM_MS = 3_800L
        private const val POST_ANIM_DWELL_MS = 1_200L
        private const val PULSE_CYCLE_MS = 2_800L
        private const val ARG_TYPE = "type"
    }

    // gates
    private lateinit var countAnimDone: CompletableDeferred<Unit>
    private var started = false
    private var navigating = false

    // media type from home (default photos)
    private val selectedType: TypeChoice by lazy {
        when (arguments?.getString(ARG_TYPE)) {
            "VIDEOS" -> TypeChoice.VIDEOS
            "AUDIO" -> TypeChoice.AUDIO
            else -> TypeChoice.PHOTOS
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) start(selectedType)
            else showPermissionDeniedState(selectedType)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _vb = FragmentScanBinding.inflate(inflater, container, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!isAndroid13Plus()) {
            showNotSupportedState()
            return
        }

        val perm = when (selectedType) {
            TypeChoice.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
            TypeChoice.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
            TypeChoice.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
        }

        when {
            hasPermission(perm) -> start(selectedType)
            shouldShowRequestPermissionRationale(perm) -> showPermissionRationaleState(selectedType)
            else -> requestPermissionLauncher.launch(perm)
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        runningAnims.forEach { it.cancel() }
        countAnimator?.cancel()
        _vb = null
        super.onDestroyView()
    }

    private fun hasPermission(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), perm) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isAndroid13Plus(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    private fun start(type: TypeChoice) {
        if (started) return
        started = true
        vb.stateGroup.visibility = View.GONE
        vb.progressGroup.visibility = View.VISIBLE
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                startPulseAnimation()
                countAnimDone = CompletableDeferred()

                val results = withContext(Dispatchers.IO) {
                    val mediaType = when (type) {
                        TypeChoice.PHOTOS -> MediaScanner.Type.PHOTOS
                        TypeChoice.VIDEOS -> MediaScanner.Type.VIDEOS
                        TypeChoice.AUDIO -> MediaScanner.Type.AUDIO
                    }

                    MediaScanner(requireContext()).scan(
                        mediaType = mediaType,
                        onProgress = { count ->
                            withContext(Dispatchers.Main) {
                                vb.totalFiles.text =
                                    getString(R.string.total_files_count, count)
                            }
                        }
                    )
                }

                countAnimDone.await()
                delay(POST_ANIM_DWELL_MS)
                if (!isActive) return@launch

                if (results.isEmpty()) showNoMediaState(type)
                else navigateToResults(type, results)
            } catch (ce: CancellationException) {
                // cancelled
            } catch (e: Exception) {
                showErrorState(type)
            } finally {
                started = false
                stopPulseAnimation()
            }
        }
        startCountAnimation()
    }

    private fun startCountAnimation() {
        countAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = COUNT_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Int
                withVb { progressPercent.text = getString(R.string.percent_format, progress) }
            }
            doOnEnd { countAnimDone.complete(Unit) }
            start()
        }
    }

    private fun startPulseAnimation() {
        val scaleAnim = ObjectAnimator.ofFloat(vb.pulseView, "scaleX", 1f, 1.2f).apply {
            duration = PULSE_CYCLE_MS
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        runningAnims.add(scaleAnim)
        scaleAnim.start()
    }

    private fun stopPulseAnimation() {
        runningAnims.forEach { it.cancel() }
        runningAnims.clear()
    }

    private fun showPermissionRationaleState(type: TypeChoice) {
        vb.stateGroup.visibility = View.VISIBLE
        vb.progressGroup.visibility = View.GONE
        vb.stateTitle.text = getString(R.string.perm_required_title)
        vb.stateMessage.text = getString(R.string.perm_required_msg)
        vb.stateButton.text = getString(R.string.perm_grant)
        vb.stateButton.setOnClickListener {
            val perm = when (type) {
                TypeChoice.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
                TypeChoice.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
                TypeChoice.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            }
            requestPermissionLauncher.launch(perm)
        }
    }

    private fun showPermissionDeniedState(type: TypeChoice) {
        vb.stateGroup.visibility = View.VISIBLE
        vb.progressGroup.visibility = View.GONE
        vb.stateTitle.text = getString(R.string.perm_required_title)
        vb.stateMessage.text = getString(R.string.perms_denied)
        vb.stateButton.text = getString(R.string.open_settings)
        vb.stateButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun showNotSupportedState() {
        vb.stateGroup.visibility = View.VISIBLE
        vb.progressGroup.visibility = View.GONE
        vb.stateTitle.text = getString(R.string.android_13_required_title)
        vb.stateMessage.text = getString(R.string.android_13_required_msg)
        vb.stateButton.text = getString(R.string.go_home)
        vb.stateButton.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun showNoMediaState(type: TypeChoice) {
        vb.stateGroup.visibility = View.VISIBLE
        vb.progressGroup.visibility = View.GONE
        vb.stateTitle.text = getString(R.string.no_media_title)
        vb.stateMessage.text = getString(R.string.no_media_msg)
        vb.stateButton.text = getString(R.string.retry)
        vb.stateButton.setOnClickListener {
            val perm = when (type) {
                TypeChoice.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
                TypeChoice.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
                TypeChoice.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            }
            if (hasPermission(perm)) start(type)
            else requestPermissionLauncher.launch(perm)
        }
    }

    private fun showErrorState(type: TypeChoice) {
        vb.stateGroup.visibility = View.VISIBLE
        vb.progressGroup.visibility = View.GONE
        vb.stateTitle.text = getString(R.string.scan_error_title)
        vb.stateMessage.text = getString(R.string.scan_error_msg)
        vb.stateButton.text = getString(R.string.retry)
        vb.stateButton.setOnClickListener {
            val perm = when (type) {
                TypeChoice.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
                TypeChoice.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
                TypeChoice.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            }
            if (hasPermission(perm)) start(type)
            else requestPermissionLauncher.launch(perm)
        }
    }

    private fun navigateToResults(type: TypeChoice, results: List<Uri>) {
        if (navigating) return
        navigating = true
        val bundle = Bundle().apply {
            putString("type", type.name)
        }
        findNavController().navigate(
            R.id.action_scanFragment_to_resultsFragment,
            bundle,
            NavOptions.Builder().setPopUpTo(R.id.scanFragment, true).build()
        )
    }
}
