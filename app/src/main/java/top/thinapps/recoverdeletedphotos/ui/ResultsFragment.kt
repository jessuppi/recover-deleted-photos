package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
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
    private val vm: ScanViewModel by activityViewModels()

    // simple selection set and current sort
    private val selected = linkedSetOf<Long>()
    private var currentSort: Sort = Sort.DATE_DESC

    private val adapter = MediaAdapter(
        isSelected = { id -> id in selected },
        onToggle = { item -> toggle(item) }
    )

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // recycler setup
        vb.list.setHasFixedSize(true)
        vb.list.layoutManager = LinearLayoutManager(requireContext())
        vb.list.adapter = adapter

        // actions
        vb.recoverButton.setOnClickListener {
            // placeholder action no file writes here
            val count = selected.size
            Toast.makeText(requireContext(), "recover $count items (coming soon)", Toast.LENGTH_SHORT).show()
        }

        // menu host for sort controls
        (requireActivity() as MenuHost).addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_results, menu)
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                when (item.itemId) {
                    R.id.action_sort_date -> setSort(Sort.DATE_DESC)
                    R.id.action_sort_size -> setSort(Sort.SIZE_DESC)
                    R.id.action_sort_name -> setSort(Sort.NAME_ASC)
                    R.id.action_select_all -> selectAll()
                    R.id.action_clear_selection -> clearSelection()
                    else -> return false
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        // initial render
        applyData()
    }

    private fun applyData() {
        val data = sortList(vm.results, currentSort)
        adapter.submitList(data)
        vb.empty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
        updateBottomBar()
    }

    private fun setSort(sort: Sort) {
        currentSort = sort
        applyData()
        Toast.makeText(requireContext(), "sorted by ${sort.label}", Toast.LENGTH_SHORT).show()
    }

    private fun selectAll() {
        selected.clear()
        sortList(vm.results, currentSort).forEach { selected += it.id }
        adapter.notifyDataSetChanged()
        updateBottomBar()
    }

    private fun clearSelection() {
        selected.clear()
        adapter.notifyDataSetChanged()
        updateBottomBar()
    }

    private fun toggle(item: MediaItem) {
        if (!selected.add(item.id)) selected.remove(item.id)
        adapter.notifyItemChanged(adapter.currentList.indexOf(item))
        updateBottomBar()
    }

    private fun updateBottomBar() {
        val count = selected.size
        vb.recoverButton.isEnabled = count > 0
        vb.recoverButton.text = if (count > 0) getString(R.string.recover_selected_count, count) else getString(R.string.recover_selected)
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }

    private fun sortList(list: List<MediaItem>, sort: Sort): List<MediaItem> = when (sort) {
        Sort.DATE_DESC -> list.sortedByDescending { it.dateAddedSec }
        Sort.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
        Sort.NAME_ASC -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: "" })
    }

    enum class Sort(val label: String) {
        DATE_DESC("date"),
        SIZE_DESC("size"),
        NAME_ASC("name")
    }

    class MediaAdapter(
        private val isSelected: (Long) -> Boolean,
        private val onToggle: (MediaItem) -> Unit
    ) : ListAdapter<MediaItem, VH>(DIFF) {

        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long = getItem(position).id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(vb, isSelected, onToggle)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

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
        private val onToggle: (MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(vb.root) {

        fun bind(it: MediaItem) {
            vb.name.text = it.displayName ?: "unknown"
            vb.meta.text = "${readableSize(it.sizeBytes)} • ${it.dateReadable}"
            vb.thumb.load(it.uri)

            // reflect selection
            vb.check.isChecked = isSelected(it.id)

            // simple click toggles selection
            vb.root.setOnClickListener { onToggle(it) }
            vb.check.setOnClickListener { onToggle(it) }
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
}
