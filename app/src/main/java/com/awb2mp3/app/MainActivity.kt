package com.awb2mp3.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val converter by lazy { NativeConverter() }
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var btnSave: Button
    private lateinit var btnFiles: Button
    private lateinit var btnFolder: Button
    private lateinit var bitrateSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView

    /** Варианты битрейта; 0 = «Авто»: для речи (AMR) как у исходного файла, для музыки 192 */
    private val bitrates = listOf(0, 16, 24, 32, 48, 64, 96, 128, 192, 256, 320)

    /** Выбранная папка сохранения (null = Музыка/AWB2MP3 по умолчанию) */
    private var saveTree: Uri? = null

    /** Файлы, ожидающие старта конвертации (пока пользователь выбирает папку) */
    private var pendingUris: List<Uri> = emptyList()

    private val prefs: SharedPreferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveTree = loadSaveTree()
        buildUi()
        updateSaveButton()
        render()
    }

    override fun onResume() {
        super.onResume()
        // экран мог быть пересоздан, пока шла конвертация, — показываем актуальное состояние
        ConversionState.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        ConversionState.listener = null
        super.onPause()
    }

    /** Отображает общее состояние конвертации (статус, прогресс, журнал, доступность кнопок) */
    private fun render() {
        val busy = ConversionState.busy
        btnFiles.isEnabled = !busy
        btnFolder.isEnabled = !busy
        btnSave.isEnabled = !busy
        bitrateSpinner.isEnabled = !busy
        status.text = ConversionState.status
        progress.progress = ConversionState.progress
        val text = ConversionState.logText()
        if (log.text.toString() != text) {
            log.text = text
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
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
        btnSave = Button(this).apply {
            isAllCaps = false
            setOnClickListener { pickSaveFolder() }
        }
        root.addView(btnSave)

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
                bitrates.map { if (it == 0) "Авто (как у исходника)" else it.toString() }
            )
            setSelection(0)
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

        if (requestCode == REQ_SAVE) {
            val tree = data?.data
            if (resultCode == RESULT_OK && tree != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // разрешение будет действовать до закрытия приложения
                }
                saveTree = tree
                prefs.edit().putString(PREF_SAVE_TREE, tree.toString()).apply()
                updateSaveButton()
            }
            // если выбор папки был открыт из диалога старта — возвращаемся к нему
            val pending = pendingUris
            if (pending.isNotEmpty()) askAndStart(pending)
            return
        }

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
                for (u in uris) keepAccess(u)
                askAndStart(uris)
            }
            REQ_FOLDER -> {
                val tree = data.data ?: return
                keepAccess(tree)
                setBusy(true, "Ищу AWB в папке…")
                worker.execute {
                    val found = mutableListOf<Uri>()
                    collectAwb(tree, DocumentsContract.getTreeDocumentId(tree), found)
                    runOnUiThread {
                        setBusy(false, "")
                        askAndStart(found)
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

    /** Просим систему сохранить доступ к выбранному файлу/папке, пока идёт долгая конвертация */
    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // не все источники поддерживают — тогда достаточно временного доступа
        }
    }

    private fun setBusy(busy: Boolean, text: String) {
        ConversionState.busy = busy
        if (text.isNotEmpty()) ConversionState.status = text
        ConversionState.changed()
    }

    private fun appendLog(line: String) {
        ConversionState.addLog(line)
        ConversionState.changed()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "audio.awb"
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio.awb"
    }

    // ---------- место сохранения ----------

    private fun loadSaveTree(): Uri? {
        val saved = prefs.getString(PREF_SAVE_TREE, null) ?: return null
        val uri = Uri.parse(saved)
        // доступ мог быть отозван — тогда возвращаемся к папке по умолчанию
        val granted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (granted) uri else null
    }

    /** Понятное название выбранной папки */
    private fun saveLabel(): String {
        val tree = saveTree ?: return "Музыка/AWB2MP3"
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use {
                if (it.moveToFirst()) return it.getString(0) ?: ""
            }
        } catch (e: Exception) {
            // ниже вернём запасной вариант
        }
        return Uri.decode(tree.lastPathSegment ?: "выбранная папка")
    }

    private fun updateSaveButton() {
        btnSave.text = "Сохранять в: ${saveLabel()}\n(нажмите, чтобы изменить)"
    }

    private fun pickSaveFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_SAVE)
    }

    /** Перед стартом конвертации спрашиваем, куда сохранять */
    private fun askAndStart(uris: List<Uri>) {
        if (ConversionState.busy) {
            Toast.makeText(this, "Конвертация уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        if (uris.isEmpty()) {
            pendingUris = emptyList()
            ConversionState.status = "Файлы AWB не найдены"
            ConversionState.changed()
            return
        }
        pendingUris = uris
        AlertDialog.Builder(this)
            .setTitle("Файлов: ${uris.size}")
            .setMessage("Сохранить MP3 в:\n${saveLabel()}")
            .setCancelable(false)
            .setPositiveButton("Начать") { _, _ ->
                pendingUris = emptyList()
                runConversion(uris)
            }
            .setNeutralButton("Другая папка…") { _, _ -> pickSaveFolder() }
            .setNegativeButton("Отмена") { _, _ -> pendingUris = emptyList() }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    /** Имена MP3, которые уже лежат в папке сохранения (для пропуска при повторном запуске) */
    private fun existingNames(): MutableSet<String> {
        val names = HashSet<String>()
        try {
            val tree = saveTree
            if (tree == null) {
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                    "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                    arrayOf("Music/AWB2MP3/"),
                    null
                )?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { names.add(it) }
                }
            } else {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                contentResolver.query(
                    children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { names.add(it) }
                }
            }
        } catch (e: Exception) {
            // не получилось узнать — просто ничего не пропускаем
        }
        return names
    }

    /** Обновляет статус, полосу и уведомление: файл [index] из [total], доля текущего файла [frac] */
    private fun report(index: Int, total: Int, name: String, frac: Double) {
        val percent = (((index + frac) / total) * 100).toInt().coerceIn(0, 100)
        val part = if (frac > 0) " (${(frac * 100).toInt()}%)" else ""
        val text = "Файл ${index + 1} из $total: $name$part"
        ConversionState.status = text
        ConversionState.progress = percent
        ConversionState.changed()
        KeepAliveService.update(this, text, percent)
    }

    private fun runConversion(uris: List<Uri>) {
        val bitrate = bitrates[bitrateSpinner.selectedItemPosition]
        ConversionState.clearLog()
        ConversionState.progress = 0
        setBusy(true, "Подготовка…")
        requestNotificationPermission()
        KeepAliveService.start(this) // служба переднего плана: работа продолжится при выключенном экране

        worker.execute {
            var ok = 0
            var skipped = 0
            var failed = 0
            try {
                val existing = existingNames()
                // обрабатываем по имени, чтобы главы шли по порядку
                val items = uris.map { it to displayName(it) }.sortedBy { it.second.lowercase() }
                val total = items.size

                items.forEachIndexed { index, (uri, name) ->
                    val base = name.substringBeforeLast('.')
                    report(index, total, name, 0.0)
                    try {
                        if (SystemDecoder.isAmr(this, uri)) {
                            // «AWB» на самом деле AMR-WB/AMR-NB: декодирует Android, кодирует LAME
                            val outName = "$base.mp3"
                            if (outName in existing) {
                                skipped++
                                appendLog("↷ $outName — уже есть, пропущен")
                            } else {
                                convertAmr(uri, outName, bitrate) { frac -> report(index, total, name, frac) }
                                existing.add(outName)
                                ok++
                                appendLog("✓ $outName")
                            }
                        } else {
                            val input = File(cacheDir, "in.awb")
                            try {
                                contentResolver.openInputStream(uri)!!.use { src ->
                                    input.outputStream().use { dst -> src.copyTo(dst) }
                                }
                                val count = converter.count(input.absolutePath)
                                if (count < 0) {
                                    failed++
                                    appendLog(
                                        "✗ $name — формат не распознан\n   ${converter.diagnose(input.absolutePath)}"
                                    )
                                } else {
                                    for (s in 1..count) {
                                        val outName = if (count == 1) "$base.mp3"
                                        else "${base}_${s.toString().padStart(3, '0')}.mp3"
                                        if (outName in existing) {
                                            skipped++
                                            appendLog("↷ $outName — уже есть, пропущен")
                                            continue
                                        }
                                        val tmp = File(cacheDir, "out.mp3")
                                        try {
                                            val error = converter.convert(
                                                input.absolutePath, if (count == 1) 0 else s,
                                                tmp.absolutePath, bitrate
                                            )
                                            if (error == null) {
                                                saveFile(tmp, outName)
                                                existing.add(outName)
                                                ok++
                                                appendLog("✓ $outName")
                                            } else {
                                                failed++
                                                appendLog("✗ $outName — $error")
                                            }
                                        } finally {
                                            tmp.delete()
                                        }
                                    }
                                }
                            } finally {
                                input.delete()
                            }
                        }
                    } catch (e: Exception) {
                        failed++
                        appendLog("✗ $name — ${e.message}")
                    }
                    report(index + 1, total, name, 0.0)
                }
            } catch (e: Exception) {
                failed++
                appendLog("✗ Ошибка: ${e.message}")
            } finally {
                val summary = "Сохранено: $ok, пропущено: $skipped, ошибок: $failed. Папка: ${saveLabel()}"
                KeepAliveService.stop(this)
                KeepAliveService.showDone(this, summary)
                ConversionState.progress = 100
                setBusy(false, "Готово. $summary")
            }
        }
    }

    /**
     * Битрейт исходного файла (размер ÷ длительность), округлённый до ближайшего
     * допустимого для MP3 на 8–16 кГц. Если длительность неизвестна — 32 кбит/с.
     */
    private fun sourceBitrateKbps(sizeBytes: Long, durationUs: Long): Int {
        val allowed = listOf(16, 24, 32, 40, 48, 56, 64)
        if (sizeBytes <= 0 || durationUs <= 0) return 32
        val kbps = sizeBytes * 8.0 / (durationUs / 1_000_000.0) / 1000.0
        return allowed.minByOrNull { Math.abs(it - kbps) } ?: 32
    }

    /** Декодирует AMR-файл системой и на лету кодирует в MP3 (без больших временных файлов) */
    private fun convertAmr(uri: Uri, outName: String, bitrate: Int, onProgress: (Double) -> Unit) {
        val tmp = File(cacheDir, "out.mp3")
        var handle = 0L
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("не удалось открыть файл")
            val sourceSize = pfd.statSize
            pfd.use {
                SystemDecoder.decode(it.fileDescriptor, object : PcmSink {
                    override fun start(sampleRate: Int, channels: Int, durationUs: Long) {
                        // «Авто»: берём битрейт исходника, чтобы размер MP3 был близок к размеру AMR
                        val kbps = if (bitrate > 0) bitrate else sourceBitrateKbps(sourceSize, durationUs)
                        handle = converter.encoderOpen(tmp.absolutePath, sampleRate, channels, kbps)
                        if (handle == 0L) throw IllegalStateException("не удалось запустить кодировщик MP3")
                    }

                    override fun write(data: ByteArray, size: Int) {
                        if (converter.encoderWrite(handle, data, size) < 0) {
                            throw IllegalStateException("ошибка кодирования или записи (нет места?)")
                        }
                    }
                }, onProgress)
            }
            if (handle == 0L) throw IllegalStateException("в файле нет звука")
            val h = handle
            handle = 0L
            if (converter.encoderClose(h) < 0) throw IllegalStateException("ошибка записи MP3")
            saveFile(tmp, outName)
        } finally {
            if (handle != 0L) converter.encoderClose(handle)
            tmp.delete()
        }
    }

    /** Сохраняет MP3 в выбранную папку или (если не выбрана) в Музыка/AWB2MP3 */
    private fun saveFile(file: File, displayName: String) {
        val tree = saveTree
        if (tree == null) {
            saveToMusic(file, displayName)
            return
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val target = DocumentsContract.createDocument(contentResolver, parent, "audio/mpeg", displayName)
            ?: throw IllegalStateException("не удалось создать файл в выбранной папке")
        try {
            contentResolver.openOutputStream(target)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            // недописанный файл не оставляем, иначе при повторном запуске он будет считаться готовым
            try {
                DocumentsContract.deleteDocument(contentResolver, target)
            } catch (ignored: Exception) {
            }
            throw e
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
        try {
            contentResolver.openOutputStream(target)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            contentResolver.delete(target, null, null)
            throw e
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
        private const val REQ_SAVE = 3
        private const val REQ_NOTIF = 4
        private const val PREF_SAVE_TREE = "save_tree"
    }
}
