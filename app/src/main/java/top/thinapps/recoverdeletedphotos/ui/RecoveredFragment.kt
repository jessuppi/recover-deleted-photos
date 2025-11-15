package top.thinapps.recoverdeletedphotos.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.Parameters
import coil.request.videoFrameMillis
import coil.size.ViewSizeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.thinapps.recoverdeletedphotos.MainActivity
import top.thinapps.recoverdeletedphotos.R
import top.thinapps.recoverdeletedphotos.databinding.FragmentRecoveredBinding
import top.thinapps.recoverdeletedphotos.databinding.ItemMediaBinding
import top.thinapps.recoverdeletedphotos.model.MediaItem
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

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
        val adapter = RecoveredAdapter { item -> openItem(item) }
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
        val perm = if (Build.VERSION.SDK_INT < 33) {
            Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            Manifest.permission.READ_MEDIA_IMAGES
        }

        return ContextCompat.checkSelfPermission(
            requireContext(), perm
        ) == PackageManager.PERMISSION_GRANTED
    }

    // query Photos + Videos under Pictures/Recovered
    private fun loadItems(): List<MediaItem> {
        val resolver = requireContext().contentResolver
        val out = mutableListOf<MediaItem>()

        fun queryCollection(collection: Uri) {
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
                    val mime = c.getString(mimeIdx) ?: ""
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

        queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

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
        } catch (_: Exception) {
            Toast.makeText(
                ctx,
                getString(R.string.recovered_open_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // same bytes → human readable helper as elsewhere
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
            .coerceAtMost(units.lastIndex)
        val scaled = bytes / 1024.0.pow(group.toDouble())
        return String.format(Locale.US, "%.1f %s", scaled, units[group])
    }

    // copied pattern from ResultsFragment: video thumb with Coil + graceful fallback
    private fun loadVideoThumbWithFallback(iv: ImageView, uri: Uri, mime: String?) {
        iv.load(uri) {
            crossfade(true)
            videoFrameMillis(0)
            allowHardware(false)
            memoryCacheKey("$uri#t=0ms")
            if (!mime.isNullOrBlank()) {
                parameters(
                    Parameters.Builder()
                        .set("coil#image_source_mime_type", mime)
                        .build()
                )
            }
            size(ViewSizeResolver(iv))
            listener(
                onError = { _, _ ->
                    val owner = iv.findViewTreeLifecycleOwner() ?: return@listener
                    owner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val w = iv.width.coerceAtLeast(200)
                            val h = iv.height.coerceAtLeast(200)
                            val thumb = iv.context.contentResolver.loadThumbnail(
                                uri,
                                Size(w, h),
                                null
                            )
                            withContext(Dispatchers.Main) {
                                iv.setImageBitmap(thumb)
                            }
                        } catch (_: Throwable) {
                            withContext(Dispatchers.Main) {
                                iv.load(uri) {
                                    crossfade(true)
                                    videoFrameMillis(1_000)
                                    allowHardware(false)
                                    memoryCacheKey("$uri#t=1000ms")
                                    if (!mime.isNullOrBlank()) {
                                        parameters(
                                            Parameters.Builder()
                                                .set("coil#image_source_mime_type", mime)
                                                .build()
                                        )
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

    // simple audio vs image/video styling
    private fun applyMediaStyling(binding: ItemMediaBinding, item: MediaItem) {
        val mt = item.mimeType.takeIf { it.isNotBlank() }
        val isVideo = item.isProbablyVideo || (mt?.startsWith("video/") == true)
        val isAudio = !isVideo && (mt?.startsWith("audio/") == true)

        binding.playIcon?.isVisible = isVideo
        binding.audioIcon?.isVisible = isAudio

        val bgColor = if (isAudio) {
            binding.thumb.resolveThemeColorInt(
                com.google.android.material.R.attr.colorSecondaryContainer,
                com.google.android.material.R.attr.colorSecondary
            )
        } else {
            Color.TRANSPARENT
        }
        binding.thumb.setBackgroundColor(bgColor)

        if (isVideo) {
            loadVideoThumbWithFallback(binding.thumb, item.uri, mt)
        } else {
            binding.thumb.load(item.uri) {
                crossfade(true)
                allowHardware(false)
            }
        }

        binding.name?.text = item.displayName
        binding.meta?.text = buildString {
            append(item.dateReadable)
            if (item.sizeBytes > 0) {
                append("\n${formatSize(item.sizeBytes)}")
            }
        }

        // recovered viewer is read-only; hide checkbox + overlay completely
        binding.check?.isVisible = false
        binding.overlay?.isVisible = false
    }

    private inner class RecoveredAdapter(
        private val click: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<RecoveredAdapter.VH>() {

        private val data = mutableListOf<MediaItem>()

        fun submit(list: List<MediaItem>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val binding: ItemMediaBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: MediaItem) {
                applyMediaStyling(binding, item)
                binding.root.setOnClickListener { click(item) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemMediaBinding.inflate(inflater, parent, false)
            return VH(binding)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(data[position])
        }
    }

    override fun onDestroyView() {
        _vb = null
        super.onDestroyView()
    }
}

// theme helper copied from ResultsFragment style
private fun View.resolveThemeColorInt(
    @AttrRes attr: Int,
    @AttrRes fallbackAttr: Int? = null
): Int {
    val ta = context.theme.obtainStyledAttributes(intArrayOf(attr))
    val color = try {
        ta.getColor(0, Color.TRANSPARENT)
    } finally {
        ta.recycle()
    }
    if (color != Color.TRANSPARENT) return color
    if (fallbackAttr == null) return color
    val fb = context.theme.obtainStyledAttributes(intArrayOf(fallbackAttr))
    return try {
        fb.getColor(0, Color.TRANSPARENT)
    } finally {
        fb.recycle()
    }
}
