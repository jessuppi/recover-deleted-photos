package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Spinner
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.*
import coil.load
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentResultsBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaGridBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import top.thinapps.recoverdeletedphotos.recover.Recovery
import java.text.Collator
import java.text.DecimalFormat
import java.util.Locale

class ResultsFragment : Fragment() {

    private var _vb: FragmentResultsBinding? = null
    private val vb get() = _vb!!

    // track selected ids for multi select
    private val selectedIds = mutableSetOf<Long>()

    // shared vm holds the results
    private val vm: ScanViewModel by activityViewModels()

    private lateinit var adapter: MediaAdapter

    // list/grid toggle (session only)
    private var useGrid = false

    // sort modes
    private enum class Sort { DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC, NAME_ASC, NAME_DESC }
    private var currentSort: Sort = Sort.DATE_DESC

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MediaAdapter(
            isSelected = { id -> id in selectedIds },
            onToggleSelect = { item -> toggleSelection(item) },
            useGridProvider = { useGrid }
        )

        vb.list.layoutManager = buildLayoutManager()
        vb.list.adapter = adapter
        vb.list.itemAnimator = DefaultItemAnimator()

        // top-right menu toggle (list ⇄ grid)
        attachMenuToggle()

        // optional sort dropdown if present in layout
        setupSortDropdownIfPresent()

        // show first pass
        applySortAndShow()

        // recover button stays disabled until something is selected
        updateRecoverButton()

        // --- Recover Selected -> copy via MediaStore into Pictures/Recovered or Music/Recovered
        vb.recoverButton.setOnClickListener {
            val chosen = adapter.currentList.filter { selectedIds.contains(it.id) }
            if (chosen.isEmpty()) return@setOnClickListener

            // In-progress feedback with a short dwell so users notice it
            vb.recoverButton.isEnabled = false
            vb.recoverButton.text = getString(R.string.recovering)
            val startMs = System.currentTimeMillis()
            val MIN_DWELL_MS = 800L

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val copied = Recovery.copyAll(requireContext(), chosen)

                    // Clear selection + unhighlight rows regardless
                    clearSelectionAndRefresh()

                    if (copied > 0) {
                        // Show destination-only message; no action button
                        val msg = buildDestMessage(chosen, copied)
                        Snackbar.make(vb.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                } finally {
                    // ensure the "Recovering…" state is visible briefly
                    val elapsed = System.currentTimeMillis() - startMs
                    if (elapsed < MIN_DWELL_MS) delay(MIN_DWELL_MS - elapsed)

                    vb.recoverButton.isEnabled = true
                    updateRecoverButton()
                }
            }
        }
    }

    // ---- menu (list ⇄ grid) ----
    private fun attachMenuToggle() {
        val host: MenuHost = requireActivity()
        host.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_results, menu)
                val item = menu.findItem(R.id.action_toggle_layout)
                item.setIcon(if (useGrid) R.drawable.ic_view_list else R.drawable.ic_view_grid)
                item.title = if (useGrid) getString(R.string.action_view_list) else getString(R.string.action_view_grid)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.action_toggle_layout) {
                    useGrid = !useGrid
                    (vb.list.layoutManager as? RecyclerView.LayoutManager)?.let {
                        vb.list.layoutManager = buildLayoutManager()
                    }
                    adapter.notifyDataSetChanged() // simple, safe refresh
                    requireActivity().invalidateOptionsMenu()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun buildLayoutManager(): RecyclerView.LayoutManager {
        return if (useGrid) {
            // 3 columns phone, 4 on sw600dp if you want later via qualifiers
            GridLayoutManager(requireContext(), 3)
        } else {
            LinearLayoutManager(requireContext())
        }
    }

    private fun setupSortDropdownIfPresent() {
        val id = resources.getIdentifier("sortDropdown", "id", requireContext().packageName)
        if (id == 0) return

        val spinner = vb.root.findViewById<Spinner>(id) ?: return
        val labels = listOf(
            getString(R.string.sort_newest_first),
            getString(R.string.sort_oldest_first),
            getString(R.string.sort_largest_first),
            getString(R.string.sort_smallest_first),
            getString(R.string.sort_name_az_full),
            getString(R.string.sort_name_za_full)
        )
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                currentSort = when (position) {
                    0 -> Sort.DATE_DESC
                    1 -> Sort.DATE_ASC
                    2 -> Sort.SIZE_DESC
                    3 -> Sort.SIZE_ASC
                    4 -> Sort.NAME_ASC
                    else -> Sort.NAME_DESC
                }
                applySortAndShow(scrollToTop = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    private fun applySortAndShow(scrollToTop: Boolean = false) {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        fun nameKey(mi: MediaItem): String = mi.displayName ?: ""

        val sorted = when (currentSort) {
            Sort.DATE_DESC -> vm.results.sortedByDescending { it.dateAddedSec }
            Sort.DATE_ASC  -> vm.results.sortedBy { it.dateAddedSec }
            Sort.SIZE_DESC -> vm.results.sortedByDescending { it.sizeBytes }
            Sort.SIZE_ASC  -> vm.results.sortedBy { it.sizeBytes }
            Sort.NAME_ASC  -> vm.results.sortedWith(compareBy(collator, ::nameKey))
            Sort.NAME_DESC -> vm.results.sortedWith(compareBy(collator, ::nameKey)).asReversed()
        }

        adapter.submitList(sorted)

        vb.empty.isVisible = sorted.isEmpty()
        vb.list.isVisible = sorted.isNotEmpty()

        if (scrollToTop && sorted.isNotEmpty()) {
            (vb.list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(0, 0)
                ?: vb.list.scrollToPosition(0)
        }
    }

    private fun toggleSelection(item: MediaItem) {
        if (!selectedIds.remove(item.id)) selectedIds.add(item.id)
        updateRecoverButton()
        val idx = adapter.currentList.indexOfFirst { it.id == item.id }
        if (idx != -1) adapter.notifyItemChanged(idx)
    }

    private fun updateRecoverButton() {
        val count = selectedIds.size
        vb.recoverButton.isEnabled = count > 0
        vb.recoverButton.text = if (count > 0) {
            getString(R.string.recover_selected_count, count)
        } else {
            getString(R.string.recover_selected)
        }
    }

    // --- UI feedback helpers ---

    private fun clearSelectionAndRefresh() {
        if (selectedIds.isEmpty()) return
        val toClear = selectedIds.toSet()
        selectedIds.clear()
        updateRecoverButton()
        adapter.currentList.forEachIndexed { index, item ->
            if (item.id in toClear) adapter.notifyItemChanged(index)
        }
    }

    private fun buildDestMessage(chosen: List<MediaItem>, copied: Int): String {
        val anyToPictures = chosen.any { uriMimeStartsWith(it, "image/") || uriMimeStartsWith(it, "video/") }
        val anyToMusic    = chosen.any { uriMimeStartsWith(it, "audio/") }

        return when {
            anyToPictures && anyToMusic ->
                getString(R.string.recover_success_multi, copied, "Pictures/Recovered", "Music/Recovered")
            anyToMusic ->
                getString(R.string.recover_success_single, copied, "Music/Recovered")
            else ->
                getString(R.string.recover_success_single, copied, "Pictures/Recovered")
        }
    }

    private fun uriMimeStartsWith(item: MediaItem, prefix: String): Boolean {
        val type = requireContext().contentResolver.getType(item.uri) ?: return false
        return type.startsWith(prefix)
    }

    override fun onDestroyView() {
        vm.results = emptyList()
        _vb = null
        super.onDestroyView()
    }

    // ---------------- Recycler adapter ----------------

    private enum class ViewType { LIST, GRID }

    class MediaAdapter(
        private val isSelected: (Long) -> Boolean,
        private val onToggleSelect: (MediaItem) -> Unit,
        private val useGridProvider: () -> Boolean
    ) : ListAdapter<MediaItem, RecyclerView.ViewHolder>(DIFF) {

        override fun getItemViewType(position: Int): Int =
            if (useGridProvider()) ViewType.GRID.ordinal else ViewType.LIST.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == ViewType.GRID.ordinal) {
                val vb = ItemMediaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                GridVH(vb, isSelected, onToggleSelect)
            } else {
                val vb = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                ListVH(vb, isSelected, onToggleSelect)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is ListVH -> holder.bind(getItem(position))
                is GridVH -> holder.bind(getItem(position))
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
                override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
                override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
            }
        }
    }

    // ----- List item VH (existing layout) -----
    class ListVH(
        private val vb: ItemMediaBinding,
        private val isSelected: (Long) -> Boolean,
        private val onToggleSelect: (MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(vb.root) {

        fun bind(item: MediaItem) {
            vb.name.text = item.displayName ?: "unknown"
            vb.meta.text = "${readableSize(item.sizeBytes)} • ${item.dateReadable}"

            // badge (Trash only)
            if (item.origin == MediaItem.Origin.TRASHED) {
                vb.badge.text = itemView.context.getString(R.string.badge_trashed)
                vb.badge.background.setTint(
                    ContextCompat.getColor(itemView.context, R.color.badge_trash)
                )
                vb.badge.visibility = View.VISIBLE
            } else vb.badge.visibility = View.GONE

            vb.thumb.load(item.uri)

            val selected = isSelected(item.id)
            vb.root.isActivated = selected
            vb.root.setBackgroundColor(
                if (selected) vb.root.context.getColor(R.color.selection_highlight)
                else vb.root.context.getColor(android.R.color.transparent)
            )

            vb.root.setOnClickListener { onToggleSelect(item) }
            vb.root.setOnLongClickListener { onToggleSelect(item); true }

            findOptionalCheckbox()?.let { check ->
                check.setOnCheckedChangeListener(null)
                check.isChecked = selected
                check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }
            }
        }

        private fun findOptionalCheckbox(): CompoundButton? {
            val root = vb.root
            val pkg = root.context.packageName
            val names = arrayOf("checkbox", "check", "select", "selectBox", "select_checkbox")
            for (n in names) {
                val id = root.resources.getIdentifier(n, "id", pkg)
                if (id != 0) {
                    val v = root.findViewById<View>(id)
                    if (v is CompoundButton) return v
                }
            }
            return null
        }

        private fun readableSize(size: Long): String {
            val kb = 1024.0
            val mb = kb * 1024
            val df = DecimalFormat("#.#")
            return when {
                size >= mb -> "${df.format(size / mb)} MB"
                size >= kb -> "${df.format(size / kb)} KB"
                else -> "$size B"
            }
        }
    }

    // ----- Grid item VH (new layout) -----
    class GridVH(
        private val vb: ItemMediaGridBinding,
        private val isSelected: (Long) -> Boolean,
        private val onToggleSelect: (MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(vb.root) {

        fun bind(item: MediaItem) {
            vb.thumb.load(item.uri)

            // badge (Trash only)
            if (item.origin == MediaItem.Origin.TRASHED) {
                vb.badge.text = itemView.context.getString(R.string.badge_trashed)
                vb.badge.visibility = View.VISIBLE
            } else vb.badge.visibility = View.GONE

            // caption (filename, single line)
            vb.caption.text = item.displayName ?: "unknown"

            val selected = isSelected(item.id)
            vb.overlay.isVisible = selected
            vb.check.setOnCheckedChangeListener(null)
            vb.check.isChecked = selected
            vb.check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }

            vb.root.setOnClickListener { onToggleSelect(item) }
            vb.root.setOnLongClickListener { onToggleSelect(item); true }
        }
    }
}
