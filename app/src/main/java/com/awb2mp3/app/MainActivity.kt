package com.awb2mp3.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val converter by lazy { NativeConverter() }
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var btnFiles: Button
    private lateinit var btnFolder: Button
    private lateinit var bitrateSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView

    private val bitrates = listOf(128, 192, 256, 320)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "AWB → MP3"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Готовые файлы сохраняются в папку Музыка/AWB2MP3"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        btnFiles = Button(this).apply {
            text = "Выбрать файлы AWB"
            setOnClickListener { pickFiles() }
        }
        btnFolder = Button(this).apply {
            text = "Выбрать папку с AWB"
            setOnClickListener { pickFolder() }
        }
        root.addView(btnFiles)
        root.addView(btnFolder)

        val bitrateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        bitrateRow.addView(TextView(this).apply { text = "Битрейт MP3, кбит/с:  " })
        bitrateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                bitrates
            )
            setSelection(1)
        }
        bitrateRow.addView(bitrateSpinner)
        root.addView(bitrateRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progress)

        status = TextView(this).apply {
            text = "Выберите файлы"
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        log = TextView(this).apply { textSize = 12f }
        logScroll = ScrollView(this).apply { addView(log) }
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_FILES)
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        when (requestCode) {
            REQ_FILES -> {
                val uris = mutableListOf<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } else {
                    data.data?.let { uris.add(it) }
                }
                startConversion(uris)
            }
            REQ_FOLDER -> {
                val tree = data.data ?: return
                setBusy(true, "Ищу AWB в папке…")
                worker.execute {
                    val found = mutableListOf<Uri>()
                    collectAwb(tree, DocumentsContract.getTreeDocumentId(tree), found)
                    runOnUiThread {
                        setBusy(false, "")
                        startConversion(found)
                    }
                }
            }
        }
    }

    /** Рекурсивно собирает все *.awb из выбранной папки */
    private fun collectAwb(tree: Uri, docId: String, out: MutableList<Uri>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collectAwb(tree, id, out)
                } else if (name.endsWith(".awb", ignoreCase = true)) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, text: String) {
        btnFiles.isEnabled = !busy
        btnFolder.isEnabled = !busy
        bitrateSpinner.isEnabled = !busy
        if (text.isNotEmpty()) status.text = text
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            log.append(line + "\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "audio.awb"
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio.awb"
    }

    private fun startConversion(uris: List<Uri>) {
        if (uris.isEmpty()) {
            status.text = "Файлы AWB не найдены"
            return
        }
        val bitrate = bitrates[bitrateSpinner.selectedItemPosition]
        log.text = ""
        progress.progress = 0
        setBusy(true, "Конвертация…")

        worker.execute {
            var ok = 0
            var failed = 0
            uris.forEachIndexed { index, uri ->
                val name = displayName(uri)
                val base = name.substringBeforeLast('.')
                runOnUiThread { status.text = "Файл ${index + 1} из ${uris.size}: $name" }

                val input = File(cacheDir, "in_$index.awb")
                try {
                    contentResolver.openInputStream(uri)!!.use { src ->
                        input.outputStream().use { dst -> src.copyTo(dst) }
                    }

                    val count = converter.count(input.absolutePath)
                    if (count < 0) {
                        failed++
                        appendLog("✗ $name — формат не распознан")
                    } else {
                        for (s in 1..count) {
                            val outName = if (count == 1) "$base.mp3"
                            else "${base}_${s.toString().padStart(3, '0')}.mp3"
                            val tmp = File(cacheDir, "out.mp3")
                            val error = converter.convert(
                                input.absolutePath, if (count == 1) 0 else s,
                                tmp.absolutePath, bitrate
                            )
                            if (error == null) {
                                saveToMusic(tmp, outName)
                                ok++
                                appendLog("✓ $outName")
                            } else {
                                failed++
                                appendLog("✗ $outName — $error")
                            }
                            tmp.delete()
                        }
                    }
                } catch (e: Exception) {
                    failed++
                    appendLog("✗ $name — ${e.message}")
                } finally {
                    input.delete()
                }
                runOnUiThread { progress.progress = (index + 1) * 100 / uris.size }
            }

            runOnUiThread {
                setBusy(false, "Готово: сохранено $ok, ошибок $failed. Папка: Музыка/AWB2MP3")
            }
        }
    }

    /** Сохраняет MP3 в Музыка/AWB2MP3 через MediaStore (разрешения не нужны) */
    private fun saveToMusic(file: File, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AWB2MP3")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val target = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("не удалось создать файл в Музыке")
        contentResolver.openOutputStream(target)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        contentResolver.update(target, values, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    companion object {
        private const val REQ_FILES = 1
        private const val REQ_FOLDER = 2
    }
}
