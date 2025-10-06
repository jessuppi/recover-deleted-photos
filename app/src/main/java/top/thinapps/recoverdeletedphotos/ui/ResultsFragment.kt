package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
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

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MediaAdapter(
            onToggleSelect = { item -> toggleSelection(item) }
        )

        vb.list.adapter = adapter
        adapter.submitList(vm.results)

        // empty state
        vb.empty.isVisible = vm.results.isEmpty()
        vb.list.isVisible = vm.results.isNotEmpty()

        // recover button starts disabled until something is selected
        updateRecoverButton()

        vb.recoverButton.setOnClickListener {
            // placeholder for future recovery action
            // you can iterate selectedIds to act on chosen items
        }
    }

    private fun toggleSelection(item: MediaItem) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
        } else {
            selectedIds.add(item.id)
        }
        updateRecoverButton()
        // update only the changed row
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
        private val onToggleSelect: (MediaItem) -> Unit
    ) : ListAdapter<MediaItem, VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemMediaBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(vb, onToggleSelect)
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
        private val onToggleSelect: (MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(vb.root) {

        fun bind(item: MediaItem) {
            // simple text binding
            vb.name.text = item.displayName ?: "unknown"
            vb.meta.text = "${readableSize(item.sizeBytes)} • ${item.dateReadable}"

            // thumbnail
            vb.thumb.load(item.uri)

            // make it obvious when selected (activated state can be styled in item layout)
            vb.root.isActivated = false // caller can tweak if you add payloads later

            // important: pass the bound item, not the view
            vb.root.setOnClickListener { onToggleSelect(item) }
            vb.root.setOnLongClickListener {
                onToggleSelect(item)
                true
            }
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
