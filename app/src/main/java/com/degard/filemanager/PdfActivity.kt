package com.degard.filemanager

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlinx.coroutines.*

class PdfActivity : AppCompatActivity() {

    private lateinit var pagesLayout: LinearLayout
    private lateinit var progress: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)

        pagesLayout = findViewById(R.id.pagesLayout)
        progress = findViewById(R.id.pdfProgress)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val path = intent.getStringExtra("path")
            ?: intent.data?.path
        if (path == null) {
            Toast.makeText(this, "No PDF provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val file = resolveFile(path)
        supportActionBar?.title = file.name

        loadPdf(file)
    }

    private fun resolveFile(path: String): File {
        val uri = intent.data
        if (uri != null && uri.scheme == "content") {
            val name = uri.lastPathSegment ?: "doc.pdf"
            val cached = File(cacheDir, name)
            return try {
                contentResolver.openInputStream(uri)?.use { input ->
                    cached.outputStream().use { output -> input.copyTo(output) }
                }
                cached
            } catch (e: Exception) {
                cached
            }
        }
        val f = File(path)
        if (f.exists()) return f
        val cached = File(cacheDir, "doc.pdf")
        try {
            contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
        }
        return cached
    }

    private fun loadPdf(file: File) {
        progress.visibility = ProgressBar.VISIBLE
        scope.launch(Dispatchers.IO) {
            val pages = renderPages(file)
            withContext(Dispatchers.Main) {
                progress.visibility = ProgressBar.GONE
                if (pages.isEmpty()) {
                    pagesLayout.visibility = View.GONE
                    val empty = TextView(this@PdfActivity).apply {
                        text = "Unable to open PDF"
                        textSize = 16f
                        setPadding(24, 24, 24, 24)
                    }
                    (pagesLayout.parent as ViewGroup).addView(empty)
                    return@withContext
                }
                for (bmp in pages) {
                    val iv = ImageView(this@PdfActivity)
                    iv.setImageBitmap(bmp)
                    iv.adjustViewBounds = true
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.setPadding(0, 8, 0, 8)
                    pagesLayout.addView(iv, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }
    }

    private fun renderPages(file: File): List<Bitmap> {
        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                return emptyList()
            }
            val maxW = 1600
            val result = mutableListOf<Bitmap>()
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val scale = minOf(1f, maxW.toFloat() / page.width)
                val w = (page.width * scale).toInt()
                val h = (page.height * scale).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                result.add(bmp)
            }
            renderer.close()
            fd.close()
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
