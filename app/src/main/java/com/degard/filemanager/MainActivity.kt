package com.degard.filemanager

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import java.io.File
import java.io.FilenameFilter
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var gridView: android.widget.GridView
    private lateinit var pathView: android.widget.TextView
    private val adapter = FileAdapter()

    private var currentDir: File = File(Environment.getExternalStorageDirectory().absolutePath)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentList = listOf<File>()
    private var sortByName = true
    private val selected = mutableSetOf<String>()
    private var selectionMode = false

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
        }

    private val requestManage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.app_name)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_revert)

        listView = findViewById(R.id.fileList)
        gridView = findViewById(R.id.fileGrid)
        pathView = findViewById(R.id.pathView)
        listView.adapter = adapter
        gridView.adapter = adapter

        val clickItem = { pos: Int ->
            val f = adapter.getItem(pos).file
            if (selectionMode) {
                toggleSelection(f)
            } else if (f.isDirectory) {
                navigate(f)
            } else {
                showFileActions(f)
            }
        }
        val longClickItem = { pos: Int ->
            val f = adapter.getItem(pos).file
            if (!selectionMode) enterSelectionMode()
            toggleSelection(f)
            true
        }
        listView.setOnItemClickListener { _, _, pos, _ -> clickItem(pos) }
        gridView.setOnItemClickListener { _, _, pos, _ -> clickItem(pos) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> longClickItem(pos) }
        gridView.setOnItemLongClickListener { _, _, pos, _ -> longClickItem(pos) }

        ensurePermission()
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!hasAllFilesAccess()) {
                AlertDialog.Builder(this)
                    .setTitle("Storage access")
                    .setMessage("Allow access to all files to browse and extract archives anywhere on the device.")
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:$packageName"))
                        manageStorageLauncher.launch(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        } else if (
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestManage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        navigate(currentDir)
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFilesAccess()) refresh() else navigate(currentDir)
    }

    private fun navigate(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        currentDir = dir
        val files = dir.listFiles(sortFilter)?.toList() ?: emptyList()
        currentList = files.sortedWith(comparator)
        adapter.gridMode = isImageFolder(files)
        updateList()
    }

    private fun isImageFolder(files: List<File>): Boolean {
        val images = files.count { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
        val regular = files.count { it.isFile }
        return regular >= 3 && images >= 3 && images * 2 >= regular
    }

    private val comparator = Comparator<File> { a, b ->
        if (a.isDirectory != b.isDirectory) {
            if (a.isDirectory) -1 else 1
        } else if (sortByName) {
            a.name.lowercase().compareTo(b.name.lowercase())
        } else {
            b.lastModified().compareTo(a.lastModified())
        }
    }

    private val sortFilter = FilenameFilter { _, name -> !name.startsWith(".") }

    private fun updateList() {
        pathView.text = currentDir.absolutePath
        supportActionBar?.title = currentDir.name.ifEmpty { "/" }
        adapter.setItems(currentList.map { FileEntry(it) })
        if (adapter.gridMode) {
            gridView.visibility = android.view.View.VISIBLE
            listView.visibility = android.view.View.GONE
        } else {
            gridView.visibility = android.view.View.GONE
            listView.visibility = android.view.View.VISIBLE
        }
    }

    private companion object {
        val IMAGE_EXTS = setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")
    }

    private fun refresh() {
        if (currentDir.exists()) navigate(currentDir) else navigate(File("/"))
    }

    override fun onSupportNavigateUp(): Boolean {
        if (selectionMode) {
            exitSelectionMode()
            return true
        }
        val parent = currentDir.parentFile
        if (parent != null) {
            navigate(parent)
        }
        return true
    }

    private fun showFileActions(f: File) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        val ext = f.extension.lowercase()

        if (ArchiveExtractor.isZip(f)) {
            options.add(Pair(getString(R.string.extract_here)) {
                runOnUiThread { doExtract(f, f.parentFile ?: f) }
            })
            options.add(Pair(getString(R.string.extract_to)) {
                chooseExtractTarget(f)
            })
        }

        if (ext in setOf("png", "webp", "jpg", "jpeg", "gif")) {
            options.add(Pair(getString(R.string.preview)) { openPreview(f) })
        }

        if (ext in setOf("txt", "doc", "docx", "log", "md", "json", "xml", "csv", "html", "htm",
                "py", "js", "ts", "kt", "java", "c", "cpp", "h", "sh", "bat", "yml", "yaml",
                "ini", "cfg", "conf", "properties", "gitignore", "env") ||
                f.name == ".gitignore" || f.name == "Dockerfile" || f.name == "Makefile") {
            options.add(Pair(if (ext in setOf("doc", "docx")) "View as text" else "View / Edit as text") { openPreview(f) })
        }

        if (ext in setOf("zip", "apk", "epub", "doc", "docx", "pdf", "xls", "xlsx",
                "ppt", "pptx", "mp3", "mp4", "mkv", "html", "htm", "png", "jpg", "jpeg",
                "webp", "gif", "txt", "csv", "mpg", "wav", "avi", "mov", "3gp", "ogg", "flac")) {
            options.add(Pair("Open with default app") { openWithDefault(f) })
        }

        if (ext == "apk") {
            options.add(Pair("Install APK") { installApk(f) })
        }

        if (ext == "html" || ext == "htm") {
            options.add(Pair("Open in browser") { openInBrowser(f) })
        }

        options.add(Pair(getString(R.string.delete)) { confirmDelete(f) })
        options.add(Pair(getString(R.string.rename)) { promptRename(f) })
        options.add(Pair("Properties") { showProperties(f) })

        val names = options.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(names) { _, which -> options[which].second() }
            .show()
    }

    private fun getUriFor(f: File): Uri {
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    }

    private fun openWithDefault(f: File) {
        try {
            val uri = getUriFor(f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMime(f.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(f: File) {
        if (!f.exists()) {
            Toast.makeText(this, "APK file not found", Toast.LENGTH_LONG).show()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow installing unknown apps")
                .setMessage(
                    "To install this APK, allow FileManager to install apps from unknown sources."
                )
                .setPositiveButton("Allow") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Open Settings → Apps → FileManager → Install unknown apps", Toast.LENGTH_LONG).show()
                    }
                    Toast.makeText(this, "After allowing, tap 'Install APK' again.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val uri = try {
            getUriFor(f)
        } catch (e: Exception) {
            Log.e("FileManager", "FileProvider error", e)
            Toast.makeText(this, "Cannot read APK: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Log.d("FileManager", "installApk uri=$uri")

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }

        val targets = listOf(
            viewIntent to "ACTION_VIEW",
            installIntent to "ACTION_INSTALL_PACKAGE"
        )
        for ((intent, tag) in targets) {
            try {
                startActivity(intent)
                Log.d("FileManager", "$tag launched")
                return
            } catch (e: Exception) {
                Log.e("FileManager", "$tag failed", e)
            }
        }
        Toast.makeText(this, "No package installer found. Allow 'Install unknown apps' for this app.", Toast.LENGTH_LONG).show()
    }

    private fun openInBrowser(f: File) {
        try {
            val uri = getUriFor(f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessMime(name: String): String {
        val map = mapOf(
            "apk" to "application/vnd.android.package-archive",
            "epub" to "application/epub+zip",
            "pdf" to "application/pdf",
            "txt" to "text/plain",
            "html" to "text/html",
            "htm" to "text/html",
            "csv" to "text/csv",
            "json" to "application/json",
            "xml" to "text/xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "mp3" to "audio/mpeg",
            "wav" to "audio/x-wav",
            "ogg" to "audio/ogg",
            "flac" to "audio/flac",
            "mp4" to "video/mp4",
            "mkv" to "video/x-matroska",
            "avi" to "video/x-msvideo",
            "mov" to "video/quicktime",
            "3gp" to "video/3gpp",
            "zip" to "application/zip",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )
        return map[name.lowercase()] ?: "*/*"
    }

    private fun chooseExtractTarget(f: File) {
        val default = f.parentFile?.let {
            File(it, f.nameWithoutExtension).apply { if (!exists()) mkdirs() }
        }
        val targets = listOfNotNull(
            default,
            File(f.parentFile, "${f.name}_extracted")
        )
        val labels = targets.map { it.absolutePath }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.extract_to))
            .setItems(labels.toTypedArray()) { _, which ->
                runOnUiThread { doExtract(f, targets[which]) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doExtract(f: File, target: File) {
        val dlg = AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage(getString(R.string.extracting))
            .setCancelable(false)
            .show()

        scope.launch(Dispatchers.IO) {
            val error = ArchiveExtractor.extractZip(f, target)
            withContext(Dispatchers.Main) {
                dlg.dismiss()
                val msg = if (error == null) {
                    getString(R.string.done) + " → " + target.absolutePath
                } else {
                    "Extract failed: $error"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun openPreview(f: File) {
        startActivity(Intent(this, PreviewActivity::class.java).putExtra("path", f.absolutePath))
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setMessage("Delete this item permanently?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    val ok = deleteRecursive(f)
                    withContext(Dispatchers.Main) {
                        if (ok) refresh() else Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteRecursive(f: File): Boolean {
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursive(it) }
        }
        return f.delete()
    }

    private fun promptRename(f: File) {
        val input = androidx.appcompat.widget.AppCompatEditText(this)
        input.setText(f.name)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != f.name) {
                    val target = File(f.parentFile, newName)
                    if (f.renameTo(target)) refresh()
                    else Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProperties(f: File) {
        val sb = StringBuilder()
        sb.append("Name: ").append(f.name).append('\n')
        sb.append("Type: ").append(if (f.isDirectory) "Folder" else {
            val mime = guessMime(f.name)
            if (mime == "*/*") f.extension.ifEmpty { "File" } else mime
        }).append('\n')
        sb.append("Path: ").append(f.absolutePath).append('\n')

        if (f.isDirectory) {
            val children = f.listFiles().orEmpty()
            val subdirs = children.count { it.isDirectory }
            val files = children.count { it.isFile }
            val totalSize = children.sumOf { if (it.isFile) it.length() else 0L }
            sb.append("Items: ").append(children.size).append('\n')
            sb.append("  Folders: ").append(subdirs).append('\n')
            sb.append("  Files: ").append(files).append('\n')
            sb.append("File size (direct): ").append(StorageInfo.formatSize(totalSize)).append('\n')
        } else {
            sb.append("Size: ").append(StorageInfo.formatSize(f.length())).append("  (${f.length()} bytes)").append('\n')
        }

        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.append("Modified: ").append(df.format(java.util.Date(f.lastModified()))).append('\n')

        if (!f.canRead()) sb.append("Readable: No\n")
        if (!f.canWrite()) sb.append("Writable: No\n")

        AlertDialog.Builder(this)
            .setTitle("Properties")
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Copy path") { _, _ ->
                (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("path", f.absolutePath))
                Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showStorageDialog() {
        val stats = StorageInfo.storageStats()
        val ram = StorageInfo.ramInfo(this)
        val msg = if (stats != null) {
            "${getString(R.string.total)}: ${StorageInfo.formatSize(stats.total)}\n" +
                "${getString(R.string.used)}: ${StorageInfo.formatSize(stats.used)}\n" +
                "${getString(R.string.free)}: ${StorageInfo.formatSize(stats.free)}\n\n$ram"
        } else {
            "Unavailable\n\n$ram"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_check)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val selMode = selectionMode
        menu.findItem(R.id.action_select)?.isVisible = !selMode
        menu.findItem(R.id.action_new_folder)?.isVisible = !selMode
        menu.findItem(R.id.action_new_file)?.isVisible = !selMode
        val hasSelection = selected.isNotEmpty()
        menu.findItem(R.id.action_select_all)?.isVisible = selMode
        menu.findItem(R.id.action_delete_selected)?.isVisible = selMode && hasSelection
        menu.findItem(R.id.action_move_selected)?.isVisible = selMode && hasSelection
        menu.findItem(R.id.action_done)?.isVisible = selMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_storage -> { showStorageDialog(); true }
            R.id.action_up -> { onSupportNavigateUp(); true }
            R.id.action_refresh -> { refresh(); true }
            R.id.action_sort_name -> { sortByName = true; refresh(); true }
            R.id.action_sort_date -> { sortByName = false; refresh(); true }
            R.id.action_select -> { enterSelectionMode(); true }
            R.id.action_new_folder -> { createNewFolder(); true }
            R.id.action_new_file -> { createNewFile(); true }
            R.id.action_select_all -> { selectAll(); true }
            R.id.action_delete_selected -> { deleteSelected(); true }
            R.id.action_move_selected -> { pickDestinationForMove(); true }
            R.id.action_done -> { exitSelectionMode(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selected.clear()
        adapter.selectionMode = true
        adapter.setSelected(emptySet())
        updateSelectionTitle()
        invalidateOptionsMenu()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        adapter.selectionMode = false
        adapter.setSelected(emptySet())
        supportActionBar?.title = currentDir.name.ifEmpty { "/" }
        invalidateOptionsMenu()
    }

    private fun toggleSelection(f: File) {
        val path = f.absolutePath
        if (selected.contains(path)) selected.remove(path) else selected.add(path)
        adapter.setSelected(path, selected.contains(path))
        updateSelectionTitle()
        invalidateOptionsMenu()
    }

    private fun selectAll() {
        val all = currentList.map { it.absolutePath }
        val allSelected = selected.size == currentList.size && currentList.isNotEmpty()
        if (allSelected) selected.clear() else selected.addAll(all)
        adapter.setSelected(selected.toSet())
        updateSelectionTitle()
        invalidateOptionsMenu()
    }

    private fun updateSelectionTitle() {
        supportActionBar?.title = "${selected.size} selected"
    }

    private fun selectedFiles(): List<File> = currentList.filter { selected.contains(it.absolutePath) }

    private fun deleteSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${files.size} item${if (files.size > 1) "s" else ""}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                scope.launch(Dispatchers.IO) {
                    var failed = 0
                    for (f in files) {
                        if (!deleteRecursive(f)) failed++
                    }
                    withContext(Dispatchers.Main) {
                        exitSelectionMode()
                        refresh()
                        if (failed > 0) {
                            Toast.makeText(this@MainActivity, "$failed item(s) could not be deleted", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDestinationForMove() {
        showFolderPicker(currentDir) { target ->
            performMove(selectedFiles(), target)
        }
    }

    private fun showFolderPicker(start: File, onPicked: (File) -> Unit) {
        val dirs = start.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        val labels = mutableListOf<String>()
        val targets = mutableListOf<File>()
        labels.add("✓ Move into: ${start.absolutePath}")
        targets.add(start)
        if (start.parentFile != null) {
            labels.add(".. / (parent)")
            targets.add(start.parentFile!!)
        }
        labels.add("＋ New folder here")
        for (d in dirs) {
            labels.add(d.name + "/")
            targets.add(d)
        }
        AlertDialog.Builder(this)
            .setTitle("Destination folder")
            .setItems(labels.toTypedArray()) { _, which ->
                val t = targets[which]
                when {
                    t == start -> onPicked(t)
                    labels[which].startsWith("＋") -> promptNewFolder(start, onPicked)
                    else -> showFolderPicker(t, onPicked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptNewFolder(parent: File, onPicked: (File) -> Unit) {
        val input = androidx.appcompat.widget.AppCompatEditText(this)
        AlertDialog.Builder(this)
            .setTitle("New folder name")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val dir = File(parent, name)
                    if (dir.mkdirs()) showFolderPicker(dir, onPicked)
                    else Toast.makeText(this, "Cannot create folder", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performMove(files: List<File>, target: File) {
        if (files.isEmpty()) return
        if (!target.exists() && !target.mkdirs()) {
            Toast.makeText(this, "Cannot create destination", Toast.LENGTH_LONG).show()
            return
        }
        for (f in files) {
            if (f == target) {
                Toast.makeText(this, "Cannot move an item into itself", Toast.LENGTH_LONG).show()
                return
            }
            if (f.isDirectory && target.absolutePath.startsWith(f.absolutePath + File.separator)) {
                Toast.makeText(this, "Cannot move a folder into itself", Toast.LENGTH_LONG).show()
                return
            }
        }
        scope.launch(Dispatchers.IO) {
            var failed = 0
            for (f in files) {
                if (!f.renameTo(File(target, f.name))) failed++
            }
            withContext(Dispatchers.Main) {
                exitSelectionMode()
                refresh()
                if (failed > 0) {
                    Toast.makeText(this@MainActivity, "$failed item(s) could not be moved", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createNewFolder() {
        val input = androidx.appcompat.widget.AppCompatEditText(this)
        input.hint = "Folder name"
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val dir = File(currentDir, name)
                if (dir.exists()) {
                    Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
                } else if (dir.mkdirs()) {
                    refresh()
                } else {
                    Toast.makeText(this, "Cannot create folder", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createNewFile() {
        val input = androidx.appcompat.widget.AppCompatEditText(this)
        input.hint = "Filename (e.g. notes.txt)"
        AlertDialog.Builder(this)
            .setTitle("New file")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val file = File(currentDir, name)
                if (file.exists()) {
                    Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        file.writeText("")
                        refresh()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Cannot create file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onBackPressed() {
        if (selectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
