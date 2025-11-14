package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.MainActivity
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentRecoveredBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem

class RecoveredFragment : Fragment() {

    private var _vb: FragmentRecoveredBinding? = null
    private val vb get() = _vb!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _vb = FragmentRecoveredBinding.inflate(inflater, container, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.setToolbarVisible(true)
        (activity as? MainActivity)?.setToolbarTitle(
            getString(R.string.recovered_media_title)
        )

        vb.recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = SimpleAdapter { item -> openItem(item) }
        vb.recycler.adapter = adapter

        if (!hasPermission()) {
            vb.stateMessage.text = getString(R.string.recovered_permission_required)
            vb.stateMessage.isVisible = true
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vb.stateMessage.isVisible = true
            vb.stateMessage.text = getString(R.string.recovered_loading)

            val list = withContext(Dispatchers.IO) { loadItems() }

            if (!isAdded) return@launch

            if (list.isEmpty()) {
                vb.stateMessage.text = getString(R.string.recovered_empty)
            } else {
                vb.stateMessage.isVisible = false
                adapter.submit(list)
            }
        }
    }

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT < 33)
            Manifest.permission.READ_EXTERNAL_STORAGE
        else
            Manifest.permission.READ_MEDIA_IMAGES

        return ContextCompat.checkSelfPermission(
            requireContext(), perm
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadItems(): List<MediaItem> {
        val resolver = requireContext().contentResolver
        val out = mutableListOf<MediaItem>()

        fun q(collection: Uri) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE
            )

            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("Pictures/Recovered%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val mimeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx) ?: "Unnamed"
                    val size = c.getLong(sizeIdx)
                    val date = c.getLong(dateIdx)
                    val mime = c.getString(mimeIdx)
                    val uri = ContentUris.withAppendedId(collection, id)

                    val item = MediaItem(
                        id = id,
                        uri = uri,
                        displayName = name,
                        sizeBytes = size,
                        dateAddedSec = date,
                        origin = MediaItem.Origin.NORMAL,
                        isProbablyVideo = mime.startsWith("video/"),
                        mimeType = mime
                    )

                    out += item
                }
            }
        }

        q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

        return out
    }

    private fun openItem(item: MediaItem) {
        val ctx = context ?: return
        val mime = if (item.mimeType.isNotBlank()) item.mimeType else "*/*"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.recovered_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private inner class SimpleAdapter(
        private val click: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<SimpleAdapter.VH>() {

        private val data = mutableListOf<MediaItem>()

        fun submit(list: List<MediaItem>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val t1: TextView = view.findViewById(android.R.id.text1)
            private val t2: TextView = view.findViewById(android.R.id.text2)

            fun bind(i: MediaItem) {
                t1.text = i.displayName
                t2.text = i.dateReadable
                itemView.setOnClickListener { click(i) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(data[position])
        }
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }
}
