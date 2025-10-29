package top.thinapps.recoverdeletedphotos.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.annotation.AttrRes
import androidx.core.content.res.use
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.load
import coil.request.Parameters
import coil.request.videoFrameMillis
import coil.size.ViewSizeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentResultsBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaGridBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import top.thinapps.recoverdeletedphotos.recover.Recovery
import top.thinapps.recoverdeletedphotos.ui.SnackbarUtils
import java.text.Collator
import kotlin.math.log10
import kotlin.math.pow

class ResultsFragment : Fragment() {

    // view binding reference
    private var _vb: FragmentResultsBinding? = null
    private val vb get() = _vb!!

    // shared viewmodel with scan results
    private val vm: ScanViewModel by activityViewModels()

    // layout state and selections
    private var useGrid = true
    private val selectedIds = linkedSetOf<Long>()
    private lateinit var adapter: MediaAdapter

    // sorting modes
    private enum class Sort { DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC, NAME_ASC, NAME_DESC }
    private var currentSort: Sort = Sort.DATE_DESC

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, container, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // handle system back same as toolbar up to always go home
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitAndCleanup()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        // init adapter and layout manager
        adapter = MediaAdapter(
            isGrid = { useGrid },
            onToggleSelect = { item -> toggleSelection(item) },
            isSelected = { id -> selectedIds.contains(id) }
        )
        vb.list.adapter = adapter
        updateLayoutManager()

        // prevent change-anim flicker on fast updates
        (vb.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        applySortAndShow()

        // show empty state if needed
        vb.empty.isVisible = adapter.itemCount == 0
        updateRecoverButton()

        // recover selected items then show snackbar confirmation
        vb.recoverButton.setOnClickListener {
            val chosen = adapter.currentList.filter { selectedIds.contains(it.id) }
            if (chosen.isEmpty()) return@setOnClickListener

            vb.recoverButton.isEnabled = false
            vb.recoverButton.text = getString(R.string.recovering)

            viewLifecycleOwner.lifecycleScope.launch {
                var recoveredCount = chosen.size
                val folderLabel = getRecoveryFolderLabel(chosen)
                val toMusic = folderLabel.contains("Music")
                try {
                    withContext(Dispatchers.IO) {
                        // copy recovered files to the chosen destination
                        Recovery.copyAll(requireContext(), chosen)
                    }
                    // clear selection and refresh
                    selectedIds.clear()
                    adapter.notifyDataSetChanged()
                    SnackbarUtils.showRecovered(requireActivity(), recoveredCount, toMusic)
                } finally {
                    // reset button state and text based on current selection
                    vb.recoverButton.isPressed = false
                    vb.recoverButton.isActivated = false
                    vb.recoverButton.isSelected = false
                    updateRecoverButton()
                    vb.recoverButton.refreshDrawableState()
                }
            }
        }

        // configure sort dropdown
        val sortLabels = listOf(
            getString(R.string.sort_newest_first),
            getString(R.string.sort_oldest_first),
            getString(R.string.sort_largest_first),
            getString(R.string.sort_smallest_first),
            getString(R.string.sort_name_az_full),
            getString(R.string.sort_name_za_full)
        )
        vb.sortDropdown.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sortLabels)

        vb.sortDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val newSort = when (position) {
                    0 -> Sort.DATE_DESC
                    1 -> Sort.DATE_ASC
                    2 -> Sort.SIZE_DESC
                    3 -> Sort.SIZE_ASC
                    4 -> Sort.NAME_ASC
                    else -> Sort.NAME_DESC
                }
                if (newSort != currentSort) {
                    currentSort = newSort
                    applySortAndShow()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // menu with grid/list toggle
        withMenu(R.menu.menu_results, onCreate = { menu ->
            val item = menu.findItem(R.id.action_toggle_layout)
            refreshToggleIcon(item)
        }) { item ->
            when (item.itemId) {
                R.id.action_toggle_layout -> {
                    useGrid = !useGrid
                    updateLayoutManager()
                    refreshToggleIcon(item)
                    true
                }
                else -> false
            }
        }
    }

    // clear results and return home
    private fun exitAndCleanup() {
        vm.results = emptyList()
        findNavController().popBackStack(R.id.homeFragment, false)
    }

    // update menu icon and tint based on current layout mode
    private fun refreshToggleIcon(item: MenuItem?) {
        if (item == null) return

        val iconTint: ColorStateList? = resolveColorStateListAttr(
            androidx.appcompat.R.attr.colorControlNormal,
            com.google.android.material.R.attr.colorOnSurface
        )

        if (useGrid) {
            item.setIcon(R.drawable.ic_view_list)
            item.title = getString(R.string.action_view_list)
        } else {
            item.setIcon(R.drawable.ic_view_grid)
            item.title = getString(R.string.action_view_grid)
        }

        item.icon?.mutate()
        if (iconTint != null) item.icon?.setTintList(iconTint)
    }

    // switch between grid and list layout managers
    private fun updateLayoutManager() {
        vb.list.layoutManager = if (useGrid) {
            GridLayoutManager(requireContext(), 3)
        } else {
            LinearLayoutManager(requireContext())
        }
        (vb.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    // sort results and submit to adapter
    private fun applySortAndShow() {
        val base = vm.results.orEmpty()

        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

        val sorted = when (currentSort) {
            Sort.DATE_DESC -> base.sortedByDescending { it.dateAddedSec }
            Sort.DATE_ASC  -> base.sortedBy { it.dateAddedSec }
            Sort.SIZE_DESC -> base.sortedByDescending { it.sizeBytes }
            Sort.SIZE_ASC  -> base.sortedBy { it.sizeBytes }
            Sort.NAME_ASC  -> base.sortedWith(compareBy(collator) { it.displayName ?: "" })
            Sort.NAME_DESC -> base.sortedWith(compareBy(collator) { it.displayName ?: "" }).asReversed()
        }

        adapter.submitList(sorted) {
            if (sorted.isNotEmpty()) {
                val lm = vb.list.layoutManager
                (lm as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
                    ?: vb.list.scrollToPosition(0)
            }
            vb.empty.isVisible = sorted.isEmpty()
        }
    }

    // toggle selection state for tapped item
    private fun toggleSelection(item: MediaItem) {
        if (!selectedIds.remove(item.id)) selectedIds.add(item.id)
        updateRecoverButton()
        val idx = adapter.currentList.indexOfFirst { it.id == item.id }
        if (idx != -1) adapter.notifyItemChanged(idx)
    }

    // update recover button enabled state and label
    private fun updateRecoverButton() {
        val count = selectedIds.size
        vb.recoverButton.isEnabled = count > 0
        vb.recoverButton.text = if (count > 0)
            getString(R.string.recover_selected) + " (" + count + ")"
        else getString(R.string.recover_selected)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _vb = null
    }

    // helpers ------------------------------------------------------------------------------

    // choose destination label based on mime type mix
    private fun getRecoveryFolderLabel(chosen: List<MediaItem>): String {
        val cr = requireContext().contentResolver
        val allAudio = chosen.all { item ->
            val mime = try { cr.getType(item.uri) } catch (_: Exception) { null }
            mime?.startsWith("audio/") == true
        }
        return if (allAudio) "Music/Recovered" else "Pictures/Recovered"
    }

    // platform-fallback loader for stubborn video frame decodes
    // coil is tried first (software bitmap + timestamp); if it errors, we call loadThumbnail() on a worker
    private fun loadVideoThumbWithFallback(iv: android.widget.ImageView, uri: android.net.Uri, mime: String?) {
        iv.load(uri) {
            crossfade(true)
            videoFrameMillis(0)               // try first keyframe
            allowHardware(false)              // force software bitmap to avoid empty HW frames
            memoryCacheKey("$uri#t=0ms")
            if (!mime.isNullOrBlank()) {
                parameters(Parameters.Builder().set("coil#image_source_mime_type", mime).build())
            }
            size(ViewSizeResolver(iv))
            listener(
                onError = { _, _ ->
                    val owner = iv.findViewTreeLifecycleOwner() ?: return@listener
                    owner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            // fall back to platform thumbnail path (different extractor/codec stack)
                            val w = iv.width.coerceAtLeast(200)
                            val h = iv.height.coerceAtLeast(200)
                            val bmp = iv.context.contentResolver.loadThumbnail(
                                uri,
                                android.util.Size(w, h),
                                android.os.CancellationSignal()
                            )
                            withContext(Dispatchers.Main) {
                                iv.setImageBitmap(bmp)
                            }
                        } catch (_: Throwable) {
                            // final retry: ask coil for a later frame (1s) which often bypasses bad first keyframes
                            withContext(Dispatchers.Main) {
                                iv.load(uri) {
                                    crossfade(true)
                                    videoFrameMillis(1_000)
                                    allowHardware(false)
                                    memoryCacheKey("$uri#t=1000ms")
                                    if (!mime.isNullOrBlank()) {
                                        parameters(Parameters.Builder().set("coil#image_source_mime_type", mime).build())
                                    }
                                    size(ViewSizeResolver(iv))
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    // adapter that renders either grid or list cells
    private inner class MediaAdapter(
        private val isGrid: () -> Boolean,
        private val onToggleSelect: (MediaItem) -> Unit,
        private val isSelected: (Long) -> Boolean
    ) : androidx.recyclerview.widget.ListAdapter<MediaItem, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
        }
    ) {
        // enable stable ids so thumbnails stay attached on fast scroll
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = getItem(position).id

        override fun getItemViewType(position: Int) = if (isGrid()) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1)
                GridVH(ItemMediaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else
                ListVH(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            when (holder) {
                is ListVH -> holder.bind(item)
                is GridVH -> holder.bind(item)
            }
        }

        // list item view holder
        private inner class ListVH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: MediaItem) {
                val mt = item.mimeType.takeIf { it.isNotBlank() }
                val isVideo = item.isProbablyVideo || (mt?.startsWith("video/") == true)

                if (isVideo) {
                    // videos: software decode + MIME hint + platform fallback on error
                    loadVideoThumbWithFallback(b.thumb, item.uri, mt)
                } else {
                    // images: normal Coil path with MIME hint (helps ambiguous extensions)
                    b.thumb.load(item.uri) {
                        crossfade(true)
                        if (mt != null) {
                            parameters(Parameters.Builder().set("coil#image_source_mime_type", mt).build())
                        }
                        size(ViewSizeResolver(b.thumb))
                    }
                }

                // play overlay only for videos
                b.playIcon?.isVisible = isVideo

                b.name?.text = item.displayName
                b.meta?.text = buildString {
                    append(item.dateReadable)
                    if (item.sizeBytes > 0) append("\n${formatSize(item.sizeBytes)}")
                }

                // selection overlay and checkbox state
                val selected = isSelected(item.id)
                b.root.findViewById<View>(R.id.overlay)?.isVisible = selected
                b.check.setOnCheckedChangeListener(null)
                b.check.isChecked = selected
                b.check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }

                // show small badge for trashed origin
                val trashed = (item.origin == MediaItem.Origin.TRASHED)
                b.badge?.isVisible = trashed

                // click handlers toggle selection
                b.root.setOnClickListener { onToggleSelect(item) }
                b.root.setOnLongClickListener { onToggleSelect(item); true }
            }
        }

        // grid item view holder
        private inner class GridVH(private val b: ItemMediaGridBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: MediaItem) {
                val mt = item.mimeType.takeIf { it.isNotBlank() }
                val isVideo = item.isProbablyVideo || (mt?.startsWith("video/") == true)

                if (isVideo) {
                    // videos: software decode + MIME hint + platform fallback on error
                    loadVideoThumbWithFallback(b.thumb, item.uri, mt)
                } else {
                    // images: normal Coil path with MIME hint (helps ambiguous extensions)
                    b.thumb.load(item.uri) {
                        crossfade(true)
                        if (mt != null) {
                            parameters(Parameters.Builder().set("coil#image_source_mime_type", mt).build())
                        }
                        size(ViewSizeResolver(b.thumb))
                    }
                }

                // play overlay only for videos
                b.playIcon?.isVisible = isVideo

                b.caption?.text = item.displayName

                // selection overlay and checkbox state
                val selected = isSelected(item.id)
                b.root.findViewById<View>(R.id.overlay)?.isVisible = selected
                b.check.setOnCheckedChangeListener(null)
                b.check.isChecked = selected
                b.check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }

                // show small badge for trashed origin
                val trashed = (item.origin == MediaItem.Origin.TRASHED)
                b.badge?.isVisible = trashed

                // click handlers toggle selection
                b.root.setOnClickListener { onToggleSelect(item) }
                b.root.setOnLongClickListener { onToggleSelect(item); true }
            }
        }
    }
}

// format readable file size
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val scaled = bytes / 1024.0.pow(group)
    return String.format("%.1f %s", scaled, units[group])
}

// resolve a color state list from a theme attr with an optional fallback attr
private fun Fragment.resolveColorStateListAttr(@AttrRes attr: Int, @AttrRes fallbackAttr: Int? = null): ColorStateList? {
    val primary = requireContext().theme.obtainStyledAttributes(intArrayOf(attr)).use { it.getColorStateList(0) }
    if (primary != null) return primary
    return if (fallbackAttr != null) {
        requireContext().theme.obtainStyledAttributes(intArrayOf(fallbackAttr)).use { it.getColorStateList(0) }
    } else null
}
