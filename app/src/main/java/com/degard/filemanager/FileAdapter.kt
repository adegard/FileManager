package com.degard.filemanager

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class FileEntry(
    val file: File,
    val isDirectory: Boolean = file.isDirectory
)

class FileAdapter : BaseAdapter() {

    private val items = mutableListOf<FileEntry>()
    private val executors: ExecutorService = Executors.newFixedThreadPool(4)
    private val handler = Handler(Looper.getMainLooper())
    private val listThumbSize = 192
    private val gridThumbSize = 480

    var gridMode: Boolean = false

    var selectionMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val selectedPaths = mutableSetOf<String>()

    fun setSelected(paths: Set<String>) {
        selectedPaths.clear()
        selectedPaths.addAll(paths)
        notifyDataSetChanged()
    }

    fun setSelected(path: String, selected: Boolean) {
        if (selected) selectedPaths.add(path) else selectedPaths.remove(path)
        notifyDataSetChanged()
    }

    fun setItems(list: List<FileEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): FileEntry = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val layoutRes = if (gridMode) R.layout.file_item_grid else R.layout.file_item
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)

        val icon = view.findViewById<ImageView>(R.id.icon)
        val name = view.findViewById<TextView>(R.id.name)
        val meta = view.findViewById<TextView>(R.id.meta)
        val check = view.findViewById<CheckBox>(R.id.check)

        val entry = items[position]
        val f = entry.file
        name.text = f.name

        val isSelected = selectedPaths.contains(f.absolutePath)
        check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        check.isChecked = isSelected
        view.setBackgroundColor(
            if (selectionMode && isSelected) parent.context.getColor(R.color.colorAccentSoft)
            else android.graphics.Color.TRANSPARENT
        )

        when {
            entry.isDirectory -> {
                icon.setImageResource(R.drawable.ic_folder)
                meta.text = "Folder"
            }
            ArchiveExtractor.isZip(f) -> {
                icon.setImageResource(R.drawable.ic_archive)
                meta.text = "${StorageInfo.formatSize(f.length())} · archive"
            }
            f.extension.lowercase() in IMAGE_EXTS -> {
                icon.setImageResource(R.drawable.ic_image)
                meta.text = "${StorageInfo.formatSize(f.length())} · image"
                val size = if (gridMode) gridThumbSize else listThumbSize
                loadThumb(icon, f, size)
            }
            f.extension.lowercase() in setOf("txt", "doc", "docx", "log", "md", "json", "xml", "csv", "html", "htm") -> {
                icon.setImageResource(R.drawable.ic_doc)
                meta.text = "${StorageInfo.formatSize(f.length())} · text"
            }
            else -> {
                icon.setImageResource(R.drawable.ic_file)
                meta.text = StorageInfo.formatSize(f.length())
            }
        }
        return view
    }

    private fun loadThumb(icon: ImageView, file: File, reqSize: Int) {
        val path = file.absolutePath
        icon.tag = path
        executors.execute {
            val bmp = decodeSampled(file, reqSize)
            if (bmp != null) {
                handler.post {
                    if (icon.tag == path) {
                        icon.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun decodeSampled(file: File, reqSize: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > reqSize || bounds.outHeight / sample > reqSize) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val IMAGE_EXTS = setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")
    }
}
