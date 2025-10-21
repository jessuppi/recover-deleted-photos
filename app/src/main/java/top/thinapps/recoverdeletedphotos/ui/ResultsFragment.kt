package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentResultsBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaGridBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import top.thinapps.recoverdeletedphotos.recover.Recovery
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

    // simple enum for sorting modes
    private enum class Sort { DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC, NAME_ASC, NAME_DESC }
    private var currentSort: Sort = Sort.DATE_DESC

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, container, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // handle system back same as toolbar up → always go home
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                findNavController().popBackStack(R.id.homeFragment, false)
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
        applySortAndShow()

        // show empty state if needed
        vb.empty.isVisible = adapter.itemCount == 0
        updateRecoverButton()

        // recover selected items
        vb.recoverButton.setOnClickListener {
            val chosen = adapter.currentList.filter { selectedIds.contains(it.id) }
            if (chosen.isEmpty()) return@setOnClickListener
            vb.recoverButton.isEnabled = false
            vb.recoverButton.text = getString(R.string.recovering)
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { Recovery.copyAll(requireContext(), chosen) }
                selectedIds.clear()
                updateRecoverButton()
                adapter.notifyDataSetChanged()
                vb.recoverButton.isEnabled = true
            }
        }

        // set up sort dropdown
        val sortLabels = listOf(
            getString(R.string.sort_newest_first),
            getString(R.string.sort_oldest_first),
            getString(R.string.sort_largest_first),
            getString(R.string.sort_smallest_first),
            getString(R.string.sort_name_az_full),
            getString(R.string.sort_name_za_full)
        )
        vb.sortDropdown.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sortLabels)
        vb.sortDropdown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentSort = when (position) {
                    0 -> Sort.DATE_DESC
                    1 -> Sort.DATE_ASC
                    2 -> Sort.SIZE_DESC
                    3 -> Sort.SIZE_ASC
                    4 -> Sort.NAME_ASC
                    else -> Sort.NAME_DESC
                }
                applySortAndShow()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // shared menu helper for grid/list toggle
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

    // update menu icon based on layout type
    private fun refreshToggleIcon(item: MenuItem?) {
        if (item == null) return
        if (useGrid) {
            item.setIcon(R.drawable.ic_view_list)
            item.title = getString(R.string.action_view_list)
        } else {
            item.setIcon(R.drawable.ic_view_grid)
            item.title = getString(R.string.action_view_grid)
        }
    }

    // update layout manager for grid/list
    private fun updateLayoutManager() {
        vb.list.layoutManager = if (useGrid) GridLayoutManager(requireContext(), 3)
        else LinearLayoutManager(requireContext())
    }

    // apply sorting and update adapter
    private fun applySortAndShow() {
        val base = vm.results
        val sorted = when (currentSort) {
            Sort.DATE_DESC -> base.sortedByDescending { it.dateAddedSec }
            Sort.DATE_ASC -> base.sortedBy { it.dateAddedSec }
            Sort.SIZE_DESC -> base.sortedByDescending { it.sizeBytes }
            Sort.SIZE_ASC -> base.sortedBy { it.sizeBytes }
            Sort.NAME_ASC -> base.sortedBy { it.displayName }
            Sort.NAME_DESC -> base.sortedByDescending { it.displayName }
        }
        adapter.submitList(sorted)
        vb.empty.isVisible = sorted.isEmpty()
    }

    // toggle selection for tapped item
    private fun toggleSelection(item: MediaItem) {
        if (!selectedIds.remove(item.id)) selectedIds.add(item.id)
        updateRecoverButton()
        val idx = adapter.currentList.indexOfFirst { it.id == item.id }
        if (idx != -1) adapter.notifyItemChanged(idx)
    }

    // enable recover button if any item selected
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

    // adapter for grid/list
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

        // list item holder
        private inner class ListVH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: MediaItem) {
                b.thumb.load(item.uri)
                b.name?.text = item.displayName
                b.meta?.text = buildString {
                    append(item.dateReadable)
                    if (item.sizeBytes > 0) append("\n${formatSize(item.sizeBytes)}")
                }
                val selected = isSelected(item.id)
                b.root.findViewById<View>(R.id.overlay)?.isVisible = selected
                b.check.setOnCheckedChangeListener(null)
                b.check.isChecked = selected
                b.check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }
                val trashed = (item.origin == MediaItem.Origin.TRASHED)
                b.badge?.isVisible = trashed
                b.root.setOnClickListener { onToggleSelect(item) }
                b.root.setOnLongClickListener { onToggleSelect(item); true }
            }
        }

        // grid item holder
        private inner class GridVH(private val b: ItemMediaGridBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: MediaItem) {
                b.thumb.load(item.uri)
                b.caption?.text = item.displayName
                val selected = isSelected(item.id)
                b.root.findViewById<View>(R.id.overlay)?.isVisible = selected
                b.check.setOnCheckedChangeListener(null)
                b.check.isChecked = selected
                b.check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }
                val trashed = (item.origin == MediaItem.Origin.TRASHED)
                b.badge?.isVisible = trashed
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
