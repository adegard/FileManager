package com.degard.filemanager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlinx.coroutines.*

class PreviewActivity : AppCompatActivity() {

    private lateinit var txtContent: TextView
    private lateinit var imgContent: ImageView
    private lateinit var progress: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var imageFile: File? = null
    private var originalBitmap: Bitmap? = null
    private var rotation = 0f

    private var imageFiles = listOf<File>()
    private var currentIndex = -1
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        txtContent = findViewById(R.id.txtContent)
        imgContent = findViewById(R.id.imgContent)
        progress = findViewById(R.id.progress)
        setSupportActionBar(findViewById(R.id.toolbar))

        val path = intent.getStringExtra("path")
        if (path == null) { finish(); return }
        val file = File(path)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); finish(); return }

        supportActionBar?.setTitle(file.name)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        imageFiles = file.parentFile?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        currentIndex = imageFiles.indexOfFirst { it.absolutePath == file.absolutePath }

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) > abs(dy) && abs(dx) > 120 && abs(velocityX) > 300) {
                    if (dx < 0) showImage(currentIndex + 1) else showImage(currentIndex - 1)
                    return true
                }
                return false
            }
        })
        imgContent.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        load(file)
    }

    private fun showImage(index: Int) {
        if (index < 0 || index >= imageFiles.size) return
        currentIndex = index
        val f = imageFiles[index]
        supportActionBar?.setTitle(f.name)
        load(f)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_preview, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_rotate -> { rotate(); true }
            R.id.action_share -> { shareImage(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun rotate() {
        val bmp = originalBitmap ?: return
        rotation = (rotation + 90f) % 360f
        imgContent.setImageBitmap(rotateBitmap(bmp, rotation))
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun shareImage() {
        val f = imageFile ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = guessMime(f.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share image"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/*"
    }

    private fun load(file: File) {
        progress.visibility = ProgressBar.VISIBLE
        val ext = file.extension.lowercase()

        if (ext in setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")) {
            imageFile = file
            invalidateOptionsMenu()
            scope.launch(Dispatchers.IO) {
                val bmp = try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (e: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    progress.visibility = ProgressBar.GONE
                    if (bmp != null) {
                        originalBitmap = bmp
                        rotation = 0f
                        txtContent.visibility = TextView.GONE
                        imgContent.visibility = ImageView.VISIBLE
                        imgContent.setImageBitmap(bmp)
                    } else {
                        txtContent.text = "Unable to decode image"
                        txtContent.visibility = TextView.VISIBLE
                        imgContent.visibility = ImageView.GONE
                    }
                }
            }
        } else {
            scope.launch(Dispatchers.IO) {
                val text = when (ext) {
                    "docx" -> readDocx(file)
                    "doc" -> "Legacy .doc binary files are not supported for text preview.\nExport as .docx or .txt for a readable preview."
                    else -> readText(file)
                }
                withContext(Dispatchers.Main) {
                    progress.visibility = ProgressBar.GONE
                    txtContent.visibility = TextView.VISIBLE
                    imgContent.visibility = ImageView.GONE
                    txtContent.text = text ?: "Unable to read file"
                }
            }
        }
    }

    private fun readText(file: File): String? {
        return try {
            val maxBytes = 2 * 1024 * 1024L
            if (file.length() > maxBytes) {
                val head = file.inputStream().use {
                    val buf = ByteArray(maxBytes.toInt())
                    val read = it.read(buf)
                    buf.copyOf(read)
                }
                String(head, Charsets.UTF_8) + "\n\n… (file truncated)"
            } else {
                file.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readDocx(file: File): String? {
        return try {
            val sb = StringBuilder()
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return@use
                val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                val paragraphs = Regex("<w:p[ >]").split(xml)
                for (p in paragraphs) {
                    val text = Regex("<[^>]+>").replace(p, "").trim()
                    if (text.isNotEmpty()) sb.append(text).append("\n")
                }
            }
            sb.toString().ifEmpty { "No readable text found in document." }
        } catch (e: Exception) {
            "Unable to parse document: ${e.message}"
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

    companion object {
        private val IMAGE_EXTS = setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")
    }
}
