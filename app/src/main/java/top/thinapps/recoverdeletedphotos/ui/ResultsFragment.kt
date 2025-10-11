package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Spinner
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentResultsBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
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

    // sort modes
    private enum class Sort {
        DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC, NAME_ASC, NAME_DESC
    }
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

            viewLifecycleOwner.lifecycleScope.launch {
                val copied = Recovery.copyAll(requireContext(), chosen)
                if (copied > 0) {
                    selectedIds.clear()
                    updateRecoverButton()
                    // Optional: add a Snackbar/Toast if you want an explicit success message.
                }
            }
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
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY // case/diacritics insensitive
        }

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

    override fun onDestroyView() {
        vm.results = emptyList()
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
            // text
            vb.name.text = item.displayName ?: "unknown"
            vb.meta.text = "${readableSize(item.sizeBytes)} • ${item.dateReadable}"

            // badge (Trash only; Hidden removed in 0.12.0)
            if (item.origin == MediaItem.Origin.TRASHED) {
                vb.badge.text = itemView.context.getString(R.string.badge_trashed)
                vb.badge.background.setTint(
                    ContextCompat.getColor(itemView.context, R.color.badge_trash)
                )
                vb.badge.visibility = View.VISIBLE
            } else {
                vb.badge.visibility = View.GONE
            }

            // thumbnail
            vb.thumb.load(item.uri)

            // selection state + light highlight
            val selected = isSelected(item.id)
            vb.root.isActivated = selected
            vb.root.setBackgroundColor(
                if (selected) vb.root.context.getColor(R.color.selection_highlight)
                else vb.root.context.getColor(android.R.color.transparent)
            )

            // clicks toggle selection
            vb.root.setOnClickListener { onToggleSelect(item) }
            vb.root.setOnLongClickListener { onToggleSelect(item); true }

            // optional checkbox support if present
            findOptionalCheckbox()?.let { check ->
                check.setOnCheckedChangeListener(null)
                check.isChecked = selected
                check.setOnCheckedChangeListener { _, _ -> onToggleSelect(item) }
            }
        }

        // try to find a checkbox by common id names if present
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

        // format bytes to kb or mb
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
