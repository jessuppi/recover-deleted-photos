package top.thinapps.recoverdeletedphotos.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.annotation.AttrRes
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load // <-- add Coil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentResultsBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaGridBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import top.thinapps.recoverdeletedphotos.recover.Recovery

class ResultsFragment : Fragment() {

    private var _vb: FragmentResultsBinding? = null
    private val vb get() = _vb!!

    private val vm: ScanViewModel by activityViewModels()

    private var useGrid = true
    private val selectedIds = linkedSetOf<Long>()
    private lateinit var adapter: MediaAdapter

    private enum class Sort { DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC, NAME_ASC, NAME_DESC }
    private var currentSort: Sort = Sort.DATE_DESC

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, container, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MediaAdapter(
            isGrid = { useGrid },
            onToggleSelect = { item -> toggleSelection(item) },
            isSelected = { id -> selectedIds.contains(id) }
        )
        vb.list.adapter = adapter
        updateLayoutManager()
        applySortAndShow()

        vb.empty.isVisible = adapter.itemCount == 0
        updateRecoverButton()

        vb.recoverButton.setOnClickListener {
            val chosen = adapter.currentList.filter { selectedIds.contains(it.id) }
            if (chosen.isEmpty()) return@setOnClickListener
            vb.recoverButton.isEnabled = false
            vb.recoverButton.text = getString(R.string.recovering)
            CoroutineScope(Dispatchers.IO).launch {
                Recovery.copyAll(requireContext(), chosen)
                CoroutineScope(Dispatchers.Main).launch {
                    selectedIds.clear()
                    updateRecoverButton()
                    adapter.notifyDataSetChanged()
                    vb.recoverButton.isEnabled = true
                }
            }
        }

        val sortLabels = listOf(
            "date (newest first)",
            "date (oldest first)",
            "size (largest first)",
            "size (smallest first)",
            "name (A→Z)",
            "name (Z→A)"
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

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.clear()
                menuInflater.inflate(R.menu.menu_results, menu)
                val item = menu.findItem(R.id.action_toggle_layout)
                refreshToggleMenuIcon(item)
                tintMenuItemIcon(item, androidx.appcompat.R.attr.colorControlNormal) // ensure visibility on light toolbar
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_toggle_layout -> {
                        useGrid = !useGrid
                        updateLayoutManager()
                        refreshToggleMenuIcon(menuItem)
                        tintMenuItemIcon(menuItem, androidx.appcompat.R.attr.colorControlNormal)
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun refreshToggleMenuIcon(item: MenuItem?) {
        if (item == null) return
        if (useGrid) {
            item.setIcon(R.drawable.ic_view_list)
            item.title = getString(R.string.action_view_list)
        } else {
            item.setIcon(R.drawable.ic_view_grid)
            item.title = getString(R.string.action_view_grid)
        }
    }

    private fun tintMenuItemIcon(item: MenuItem?, @AttrRes attr: Int) {
        if (item?.icon == null) return
        val color = resolveAttrColor(attr)
        val wrapped = DrawableCompat.wrap(item.icon!!)
        DrawableCompat.setTint(wrapped, color)
        item.icon = wrapped
        item.iconTintList = ColorStateList.valueOf(color)
    }

    private fun resolveAttrColor(@AttrRes attr: Int): Int {
        val ta = requireContext().theme.obtainStyledAttributes(intArrayOf(attr))
        return try { ta.getColor(0, 0xFF000000.toInt()) } finally { ta.recycle() }
    }

    private fun updateLayoutManager() {
        vb.list.layoutManager = if (useGrid) {
            GridLayoutManager(requireContext(), 3)
        } else {
            LinearLayoutManager(requireContext())
        }
    }

    private fun applySortAndShow() {
        val base = vm.results
        val sorted = when (currentSort) {
            Sort.DATE_DESC -> base.sortedByDescending { it.dateAddedSec }
            Sort.DATE_ASC  -> base.sortedBy { it.dateAddedSec }
            Sort.SIZE_DESC -> base.sortedByDescending { it.sizeBytes }
            Sort.SIZE_ASC  -> base.sortedBy { it.sizeBytes }
            Sort.NAME_ASC  -> base.sortedBy { it.displayName }
            Sort.NAME_DESC -> base.sortedByDescending { it.displayName }
        }
        adapter.submitList(sorted)
        vb.empty.isVisible = sorted.isEmpty()
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
            getString(R.string.recover_selected) + " (" + count + ")"
        } else {
            getString(R.string.recover_selected)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _vb = null
    }

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
        override fun getItemViewType(position: Int): Int = if (isGrid()) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) {
                val b = ItemMediaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                GridVH(b)
            } else {
                val b = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                ListVH(b)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            when (holder) {
                is ListVH -> holder.bind(item)
                is GridVH -> holder.bind(item)
            }
        }

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
                b.root.setOnClickListener { onToggleSelect(item) }
                b.root.setOnLongClickListener { onToggleSelect(item); true }
            }
        }

        private inner class GridVH(private val b: ItemMediaGridBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: MediaItem) {
                b.thumb.load(item.uri)
                b.caption?.text = item.displayName
                val selected = isSelected(item.id)
                b.root.findViewById<View>(R.id.overlay)?.isVisible = selected
                b.check.setOnCheckedChangeListener(null)
                b.check.isChecked = selected
                b.check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }
                b.root.setOnClickListener { onToggleSelect(item) }
                b.root.setOnLongClickListener { onToggleSelect(item); true }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val scaled = bytes / Math.pow(1024.0, group.toDouble())
    return String.format("%.1f %s", scaled, units[group])
}
