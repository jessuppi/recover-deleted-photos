package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentScanBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import top.thinapps.recoverdeletedphotos.scan.MediaScanner

class ScanFragment : Fragment() {

    private enum class TypeChoice { PHOTOS, VIDEOS, AUDIO }

    private var _vb: FragmentScanBinding? = null
    private val vb get() = _vb!!
    private var job: Job? = null
    private val vm: ScanViewModel by activityViewModels()

    // animations
    private val runningAnims = mutableListOf<ObjectAnimator>()
    private var countAnimator: ValueAnimator? = null

    // timing
    private val COUNT_ANIM_MS = 3800L
    private val POST_ANIM_DWELL_MS = 1200L
    private val PULSE_CYCLE_MS = 2800L

    // gates
    private lateinit var countAnimDone: CompletableDeferred<Unit>
    private var started = false
    private var navigating = false

    // storage for saf tree uri
    private val prefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences("prefs", 0)
    }

    // media type from Home (default PHOTOS)
    private val selectedType: TypeChoice by lazy {
        when (arguments?.getString("type")) {
            "VIDEOS" -> TypeChoice.VIDEOS
            "AUDIO"  -> TypeChoice.AUDIO
            else     -> TypeChoice.PHOTOS
        }
    }

    // permission launcher for selected type
    private val requestPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val wanted = requiredPerms(selectedType)
        val granted = wanted.all { result[it] == true }
        if (granted) {
            if (!started) { started = true; start(selectedType) }
        } else {
            showPermissionState()
        }
    }

    // pick a saf tree once to include hidden/.nomedia
    private val safPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        prefs.edit().putString(KEY_SAF_TREE_URI, uri.toString()).apply()
        // optional: user granted mid-scan; next scans will include hidden automatically
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentScanBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vb.cancelButton.setOnClickListener { cancel() }

        val t = selectedType
        if (hasPermission(t)) {
            if (!started) { started = true; start(t) }
        } else {
            showPermissionState()
        }
    }

    // ---------- permissions ----------

    private fun requiredPerms(type: TypeChoice): Array<String> =
        if (Build.VERSION.SDK_INT < 33) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            when (type) {
                TypeChoice.PHOTOS -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                TypeChoice.VIDEOS -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                TypeChoice.AUDIO  -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }

    private fun hasPermission(type: TypeChoice): Boolean =
        requiredPerms(type).all { p ->
            ContextCompat.checkSelfPermission(requireContext(), p) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun permanentlyDenied(type: TypeChoice): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            val show = shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
            return !show && !hasPermission(type)
        }
        val perms = requiredPerms(type)
        val anyShow = perms.any { shouldShowRequestPermissionRationale(it) }
        return !anyShow && !hasPermission(type)
    }

    // ---------- flow ----------

    private fun start(type: TypeChoice) {
        showScanUI(true)
        job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                // init UI
                vb.totalLabel.text = when (type) {
                    TypeChoice.PHOTOS -> getString(R.string.total_photos_label)
                    TypeChoice.VIDEOS -> getString(R.string.total_videos_label)
                    TypeChoice.AUDIO  -> getString(R.string.total_audio_label)
                }

                vb.totalCount.text = getString(R.string.total_files_count, 0)
                countAnimDone = CompletableDeferred()
                startPulses()

                // start a saf crawl in parallel if we already have a persisted grant
                val safUri = prefs.getString(KEY_SAF_TREE_URI, null)?.let { Uri.parse(it) }
                val safDeferred = if (safUri != null) {
                    val root = DocumentFile.fromTreeUri(requireContext(), safUri)
                    async(Dispatchers.IO) { crawlHidden(root) }
                } else {
                    // first run: prompt once, but do not block the scan
                    promptSafGrantNonBlocking()
                    null
                }

                // run the mediascan (includes trashed, excludes pending)
                val items = withContext(Dispatchers.IO) {
                    var totalSeen = 0
                    MediaScanner(requireContext().applicationContext).scan(
                        includeImages = type == TypeChoice.PHOTOS,
                        includeVideos = type == TypeChoice.VIDEOS,
                        includeAudio  = type == TypeChoice.AUDIO
                    ) { _, total ->
                        if (totalSeen == 0 && total > 0) {
                            totalSeen = total
                            viewLifecycleOwner.lifecycleScope.launch { animateCountTo(totalSeen) }
                        }
                    }
                }

                // if we had a saf grant, wait for crawl and merge
                val hidden = safDeferred?.await() ?: emptyList()
                val merged = mergeByUri(items, hidden)

                if (merged.isEmpty()) {
                    stopPulses()
                    showNoMediaState()
                    return@launch
                }

                vm.results = merged

                countAnimDone.await()

                val current = findNavController().currentDestination?.id
                if (!navigating && isResumed && current == R.id.scanFragment) {
                    navigating = true
                    vb.cancelButton.isEnabled = false
                    val opts = NavOptions.Builder().setPopUpTo(R.id.scanFragment, true).build()
                    findNavController().navigate(R.id.action_scan_to_results, null, opts)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                stopPulses()
                showErrorState()
            }
        }
    }

    // ask once for a saf tree to include hidden/.nomedia; continue scanning regardless
    private fun promptSafGrantNonBlocking() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        safPicker.launch(intent)
    }

    // crawl hidden/.nomedia under the granted tree and return media items
    private suspend fun crawlHidden(root: DocumentFile?): List<MediaItem> = withContext(Dispatchers.IO) {
        if (root == null) return@withContext emptyList()
        val out = ArrayList<MediaItem>(128)

        fun isMedia(df: DocumentFile): Boolean {
            val mime = df.type ?: return false
            return mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
        }

        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { f ->
                if (f.isDirectory) {
                    // skip restricted trees like /Android
                    if (f.uri.toString().contains("/document/primary:Android/")) return@forEach
                    walk(f)
                } else if (isMedia(f)) {
                    out.add(
                        MediaItem(
                            id = f.uri.hashCode().toLong(),
                            uri = f.uri,
                            displayName = f.name ?: "unknown",
                            sizeBytes = f.length(),
                            dateAddedSec = (f.lastModified() / 1000L)
                        )
                    )
                }
            }
        }

        walk(root)
        out
    }

    // merge by uri, keeping first occurrence
    private fun mergeByUri(a: List<MediaItem>, b: List<MediaItem>): List<MediaItem> {
        if (b.isEmpty()) return a
        val seen = HashSet<Uri>(a.size + b.size)
        val out = ArrayList<MediaItem>(a.size + b.size)
        a.forEach {
            if (seen.add(it.uri)) out.add(it)
        }
        b.forEach {
            if (seen.add(it.uri)) out.add(it)
        }
        return out
    }

    private suspend fun animateCountTo(target: Int) {
        countAnimator?.cancel()
        countAnimator = ValueAnimator.ofInt(0, target).apply {
            duration = COUNT_ANIM_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Int
                vb.totalCount.text = getString(R.string.total_files_count, v)
            }
            doOnEnd {
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(POST_ANIM_DWELL_MS)
                    if (!countAnimDone.isCompleted) countAnimDone.complete(Unit)
                }
            }
        }
        countAnimator?.start()
    }

    private fun startPulses() {
        fun pulse(view: View, delayMs: Long) {
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.6f).apply {
                duration = PULSE_CYCLE_MS
                interpolator = FastOutSlowInInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                startDelay = delayMs
            }
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.6f).apply {
                duration = PULSE_CYCLE_MS
                interpolator = FastOutSlowInInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                startDelay = delayMs
            }
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 0.22f, 0f).apply {
                duration = PULSE_CYCLE_MS
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                startDelay = delayMs
            }
            runningAnims += listOf(scaleX, scaleY, alpha)
            scaleX.start(); scaleY.start(); alpha.start()
        }
        pulse(vb.pulse1, 0)
        pulse(vb.pulse2, PULSE_CYCLE_MS / 2)
    }

    private fun stopPulses() {
        runningAnims.forEach { it.cancel() }
        runningAnims.clear()
        countAnimator?.cancel()
        countAnimator = null
        vb.pulse1.alpha = 0f
        vb.pulse1.scaleX = 1f
        vb.pulse1.scaleY = 1f
        vb.pulse2.alpha = 0f
        vb.pulse2.scaleX = 1f
        vb.pulse2.scaleY = 1f
    }

    private fun cancel() {
        job?.cancel()
        stopPulses()
        val nav = findNavController()
        if (!navigating) {
            navigating = true
            vb.cancelButton.isEnabled = false
            if (!nav.popBackStack(R.id.homeFragment, false)) {
                nav.navigate(
                    R.id.homeFragment,
                    null,
                    NavOptions.Builder().setPopUpTo(R.id.homeFragment, false).build()
                )
            }
        }
    }

    // ---------- UI state toggles ----------

    private fun showScanUI(show: Boolean) {
        vb.scanContent.visibility = if (show) View.VISIBLE else View.GONE
        vb.stateContainer.visibility = if (show) View.GONE else View.VISIBLE
        vb.cancelButton.isEnabled = show && !navigating
    }

    private fun showPermissionState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.perm_required_title)
        vb.stateMessage.text = getString(R.string.perm_required_msg)
        val t = selectedType
        vb.statePrimary.text = if (permanentlyDenied(t)) getString(R.string.open_settings) else getString(R.string.perm_grant)
        vb.statePrimary.setOnClickListener {
            if (permanentlyDenied(t)) {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(i)
            } else {
                requestPerm.launch(requiredPerms(t))
            }
        }
        vb.stateSecondary.apply {
            text = getString(R.string.go_home)
            visibility = View.VISIBLE
            setOnClickListener { findNavController().popBackStack(R.id.homeFragment, false) }
        }
    }

    private fun showNoMediaState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.no_media_title)
        vb.stateMessage.text = getString(R.string.no_media_msg)
        vb.statePrimary.text = getString(R.string.retry)
        vb.statePrimary.setOnClickListener {
            val t = selectedType
            if (hasPermission(t)) {
                if (!started) { started = true }
                start(t)
            } else showPermissionState()
        }
        vb.stateSecondary.apply {
            text = getString(R.string.go_home)
            visibility = View.VISIBLE
            setOnClickListener { findNavController().popBackStack(R.id.homeFragment, false) }
        }
    }

    private fun showErrorState() {
        showScanUI(false)
        vb.stateTitle.text = getString(R.string.scan_error_title)
        vb.stateMessage.text = getString(R.string.scan_error_msg)
        vb.statePrimary.text = getString(R.string.retry)
        vb.statePrimary.setOnClickListener {
            val t = selectedType
            if (hasPermission(t)) {
                if (!started) { started = true }
                start(t)
            } else showPermissionState()
        }
        vb.stateSecondary.apply {
            text = getString(R.string.go_home)
            visibility = View.VISIBLE
            setOnClickListener { findNavController().popBackStack(R.id.homeFragment, false) }
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        stopPulses()
        _vb = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_SAF_TREE_URI = "saf_tree"
    }
}
