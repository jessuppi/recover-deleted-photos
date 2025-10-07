package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Spinner
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentResultsBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import java.text.DecimalFormat

class ResultsFragment : Fragment() {

    private var _vb: FragmentResultsBinding? = null
    private val vb get() = _vb!!

    // keep track of selected ids for multi select
    private val selectedIds = mutableSetOf<Long>()

    // shared vm holds the results
    private val vm: ScanViewModel by activityViewModels()

    private lateinit var adapter: MediaAdapter

    // simple sort modes
    private enum class Sort { DATE_DESC, SIZE_DESC, NAME_ASC }
    private var currentSort: Sort = Sort.DATE_DESC

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MediaAdapter(
            isSelected = { id -> id in selectedIds },
            onToggleSelect = { item -> toggleSelection(item) }
        )

        vb.list.layoutManager = LinearLayoutManager(requireContext())
        vb.list.adapter = adapter

        // set up optional sort dropdown if it exists in the layout
        setupSortDropdownIfPresent()

        // show initial list with chosen sort
        applySortAndShow()

        // recover button remains disabled until something is selected
        updateRecoverButton()

        vb.recoverButton.setOnClickListener {
            // placeholder for future recovery action
            // iterate selectedIds for selected items
        }
    }

    private fun setupSortDropdownIfPresent() {
        val res = resources
        val id = res.getIdentifier("sortDropdown", "id", requireContext().packageName)
        if (id == 0) return

        val spinner = vb.root.findViewById<Spinner>(id) ?: return
        val labels = listOf("Date (newest)", "Size", "Name")
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                v: View?,
                position: Int,
                rowId: Long
            ) {
                currentSort = when (position) {
                    0 -> Sort.DATE_DESC
                    1 -> Sort.SIZE_DESC
                    else -> Sort.NAME_ASC
                }
                applySortAndShow()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    private fun applySortAndShow() {
        val sorted = when (currentSort) {
            Sort.DATE_DESC -> vm.results.sortedByDescending { it.dateAddedSec }
            Sort.SIZE_DESC -> vm.results.sortedByDescending { it.sizeBytes }
            Sort.NAME_ASC -> vm.results.sortedBy { it.displayName?.lowercase() ?: "" }
        }
        adapter.submitList(sorted)

        vb.empty.isVisible = sorted.isEmpty()
        vb.list.isVisible = sorted.isNotEmpty()
    }

    private fun toggleSelection(item: MediaItem) {
        if (selectedIds.remove(item.id).not()) {
            selectedIds.add(item.id)
        }
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

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }

    // recycler adapter

    class MediaAdapter(
        private val isSelected: (Long) -> Boolean,
        private val onToggleSelect: (MediaItem) -> Unit
    ) : ListAdapter<MediaItem, VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemMediaBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(vb, isSelected, onToggleSelect)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
                override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
                override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
            }
        }
    }

    class VH(
        private val vb: ItemMediaBinding,
        private val isSelected: (Long) -> Boolean,
        private val onToggleSelect: (MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(vb.root) {

        fun bind(item: MediaItem) {
            // text fields
            vb.name.text = item.displayName ?: "unknown"
            vb.meta.text = "${readableSize(item.sizeBytes)} • ${item.dateReadable}"

            // thumbnail
            vb.thumb.load(item.uri)

            // selected state hint on root
            vb.root.isActivated = isSelected(item.id)

            // root click toggles selection
            vb.root.setOnClickListener { onToggleSelect(item) }
            vb.root.setOnLongClickListener {
                onToggleSelect(item)
                true
            }

            // also support an optional checkbox in the item layout, without hardcoding its id
            val cb = findOptionalCheckbox()
            cb?.let { check ->
                // avoid listener firing during programmatic changes
                check.setOnCheckedChangeListener(null)
                check.isChecked = isSelected(item.id)
                check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }
            }
        }

        // try to find a checkbox by common id names if present
        private fun findOptionalCheckbox(): CompoundButton? {
            val root = vb.root
            val pkg = root.context.packageName
            val names = arrayOf(
                "checkbox", "check", "select", "selectBox", "select_checkbox"
            )
            for (n in names) {
                val id = root.resources.getIdentifier(n, "id", pkg)
                if (id != 0) {
                    val v = root.findViewById<View>(id)
                    if (v is CompoundButton) return v
                }
            }
            return null
        }

        // format bytes to kb or mb for quick display
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
}
