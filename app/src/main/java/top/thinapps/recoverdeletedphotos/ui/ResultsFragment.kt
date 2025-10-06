package top.thinapps.recoverdeletedphotos.ui

import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import top.thinapps.recoverdeletedphotos.databinding.FragmentResultsBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import java.text.DecimalFormat

class ResultsFragment : Fragment() {
    private var _vb: FragmentResultsBinding? = null
    private val vb get() = _vb!!
    private val adapter = MediaAdapter()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _vb = FragmentResultsBinding.inflate(inflater, c, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vb.list.adapter = adapter
        val items = requireArguments().getParcelableArrayList<MediaItem>(KEY_ITEMS) ?: arrayListOf()
        adapter.submitList(items)
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }

    class MediaAdapter : ListAdapter<MediaItem, VH>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(vb)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
                override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.id == b.id
                override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
            }
        }
    }

    class VH(private val vb: ItemMediaBinding) : RecyclerView.ViewHolder(vb.root) {
        fun bind(it: MediaItem) {
            vb.name.text = it.displayName ?: "unknown"

            // convert size manually to readable string to prevent crash
            val readableSize = readableSize(it.sizeBytes)
            vb.meta.text = "$readableSize • ${it.dateReadable}"

            vb.thumb.load(it.uri)
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

    companion object {
        private const val KEY_ITEMS = "items"
        fun args(items: List<MediaItem>) = bundleOf(KEY_ITEMS to ArrayList(items))
    }
}
