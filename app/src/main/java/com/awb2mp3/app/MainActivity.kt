package com.awb2mp3.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
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
    private lateinit var btnStart: Button
    private lateinit var btnClear: Button
    private lateinit var bitrateSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView
    private val adapter = QueueAdapter()

    /** Варианты битрейта; 0 = «Авто»: для речи (AMR) как у исходного файла, для музыки 192 */
    private val bitrates = listOf(0, 16, 24, 32, 48, 64, 96, 128, 192, 256, 320)

    /** Выбранная папка сохранения (null = Музыка/AWB2MP3 по умолчанию) */
    private var saveTree: Uri? = null

    /** Пользователь ушёл выбирать другую папку из окна старта — после выбора окно покажем снова */
    private var resumeStartDialog = false

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

    /** Отображает очередь, статус, общий прогресс и доступность кнопок */
    private fun render() {
        val busy = ConversionState.busy
        val items = ConversionState.snapshot()
        val pending = items.count { it.state == ItemState.PENDING || it.state == ItemState.FAILED }

        btnFiles.isEnabled = !busy
        btnFolder.isEnabled = !busy
        btnSave.isEnabled = !busy
        bitrateSpinner.isEnabled = !busy
        btnStart.isEnabled = !busy && pending > 0
        btnStart.text = if (pending > 0) "Начать конвертацию ($pending)" else "Начать конвертацию"
        btnClear.isEnabled = !busy && items.isNotEmpty()

        status.text = ConversionState.status
        progress.progress = ConversionState.progress
        adapter.submit(items)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "AWB → MP3"
            textSize = 22f
            setPadding(dp(4), 0, 0, dp(4))
        })

        btnSave = Button(this).apply {
            isAllCaps = false
            setOnClickListener { pickSaveFolder() }
        }
        root.addView(btnSave)

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnFiles = Button(this).apply {
            text = "+ Файлы"
            setOnClickListener { pickFiles() }
        }
        btnFolder = Button(this).apply {
            text = "+ Папка"
            setOnClickListener { pickFolder() }
        }
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addRow.addView(btnFiles, half)
        addRow.addView(btnFolder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(addRow)

        val bitrateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), 0, dp(2))
        }
        bitrateRow.addView(TextView(this).apply { text = "Битрейт, кбит/с:  " })
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

        val startRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = Button(this).apply {
            text = "Начать конвертацию"
            setOnClickListener { onStartClicked() }
        }
        btnClear = Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                ConversionState.clearAll()
                ConversionState.progress = 0
                ConversionState.status = "Добавьте файлы AWB"
                ConversionState.changed()
            }
        }
        startRow.addView(btnStart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        startRow.addView(btnClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(startRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(status)

        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            isVerticalScrollBarEnabled = true
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // ---------- список файлов ----------

    private inner class RowHolder {
        lateinit var icon: TextView
        lateinit var name: TextView
        lateinit var bar: ProgressBar
        lateinit var message: TextView
        lateinit var close: ImageButton
        var item: QueueItem? = null

        fun build(): View {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            icon = TextView(this@MainActivity).apply {
                textSize = 20f
                gravity = Gravity.CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))

            val middle = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            name = TextView(this@MainActivity).apply {
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
            }
            message = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.GRAY)
            }
            middle.addView(name)
            middle.addView(bar)
            middle.addView(message)
            row.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            close = ImageButton(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "Убрать из списка"
                setOnClickListener {
                    item?.let {
                        if (ConversionState.remove(it)) ConversionState.changed()
                    }
                }
            }
            row.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
            return row
        }

        fun bind(it: QueueItem) {
            item = it
            name.text = it.name
            when (it.state) {
                ItemState.PENDING -> {
                    icon.text = "○"; icon.setTextColor(Color.GRAY)
                }
                ItemState.RUNNING -> {
                    icon.text = "▶"; icon.setTextColor(Color.parseColor("#1565C0"))
                }
                ItemState.DONE -> {
                    icon.text = "✓"; icon.setTextColor(Color.parseColor("#2E7D32"))
                }
                ItemState.SKIPPED -> {
                    icon.text = "↷"; icon.setTextColor(Color.GRAY)
                }
                ItemState.FAILED -> {
                    icon.text = "✗"; icon.setTextColor(Color.parseColor("#C62828"))
                }
            }
            val running = it.state == ItemState.RUNNING
            bar.visibility = if (running) View.VISIBLE else View.GONE
            bar.progress = it.percent
            val msg = if (running && it.percent > 0) "${it.percent}%  ${it.message}".trim() else it.message
            message.text = msg
            message.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
            // удалять можно всё, кроме файла, который конвертируется прямо сейчас
            close.visibility = if (running) View.INVISIBLE else View.VISIBLE
        }
    }

    private inner class QueueAdapter : BaseAdapter() {
        private var data: List<QueueItem> = emptyList()

        fun submit(items: List<QueueItem>) {
            data = items
            notifyDataSetChanged()
        }

        override fun getCount() = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: RowHolder
            val view: View
            if (convertView == null) {
                holder = RowHolder()
                view = holder.build()
                view.tag = holder
            } else {
                view = convertView
                holder = convertView.tag as RowHolder
            }
            holder.bind(data[position])
            return view
        }
    }

    // ---------- выбор файлов ----------

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

    /** Добавляет выбранные файлы в список (имена читаем в фоне, чтобы не тормозить экран) */
    private fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            ConversionState.status = "Файлы AWB не найдены"
            ConversionState.changed()
            return
        }
        worker.execute {
            val added = ConversionState.add(uris.map { QueueItem(it, displayName(it)) })
            ConversionState.status = if (added > 0) "Добавлено файлов: $added. Нажмите «Начать конвертацию»."
            else "Эти файлы уже в списке"
            ConversionState.changed()
        }
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
            // если выбор папки был открыт из окна старта — возвращаемся к нему
            if (resumeStartDialog) {
                resumeStartDialog = false
                showStartDialog()
            }
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
                addUris(uris)
            }
            REQ_FOLDER -> {
                val tree = data.data ?: return
                keepAccess(tree)
                setBusy(true, "Ищу AWB в папке…")
                worker.execute {
                    val found = mutableListOf<Uri>()
                    try {
                        collectAwb(tree, DocumentsContract.getTreeDocumentId(tree), found)
                    } finally {
                        setBusy(false, "")
                    }
                    runOnUiThread { addUris(found) }
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

    /** Нажали «Начать»: если есть что конвертировать, спрашиваем, куда сохранять */
    private fun onStartClicked() {
        if (ConversionState.busy) {
            Toast.makeText(this, "Конвертация уже идёт", Toast.LENGTH_SHORT).show()
            return
        }
        if (ConversionState.pendingCount() == 0) {
            Toast.makeText(this, "Нет файлов для конвертации", Toast.LENGTH_SHORT).show()
            return
        }
        showStartDialog()
    }

    private fun showStartDialog() {
        val count = ConversionState.pendingCount()
        if (count == 0 || ConversionState.busy) return
        AlertDialog.Builder(this)
            .setTitle("Файлов: $count")
            .setMessage("Сохранить MP3 в:\n${saveLabel()}")
            .setCancelable(false)
            .setPositiveButton("Начать") { _, _ -> runQueue() }
            .setNeutralButton("Другая папка…") { _, _ ->
                resumeStartDialog = true
                pickSaveFolder()
            }
            .setNegativeButton("Отмена", null)
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

    /** Время в виде 1:02:03 или 02:03 */
    private fun fmtTime(totalSec: Long): String {
        val t = if (totalSec < 0) 0 else totalSec
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun fmtSize(bytes: Long): String = "%.1f МБ".format(bytes / 1048576.0)

    /**
     * Обновляет статус, общий прогресс и уведомление.
     * [processedUs] и [durationUs] — сколько звука текущего файла уже обработано и сколько его всего
     * (0, если неизвестно).
     */
    private fun report(item: QueueItem, processedUs: Long = 0, durationUs: Long = 0) {
        if (durationUs > 0) {
            item.percent = ((processedUs.toDouble() / durationUs) * 100).toInt().coerceIn(0, 100)
            item.message = "${fmtTime(processedUs / 1_000_000)} из ${fmtTime(durationUs / 1_000_000)}"
        } else if (processedUs > 0) {
            item.message = "обработано ${fmtTime(processedUs / 1_000_000)}"
        }

        val (finished, total) = ConversionState.runProgress()
        val percent = ConversionState.overallPercent()
        val elapsedSec = (System.currentTimeMillis() - ConversionState.startedAt) / 1000

        val line1 = "Файл ${minOf(finished + 1, total)} из $total: ${item.name}"
        val line2 = StringBuilder("Общий ход: $percent%, прошло ${fmtTime(elapsedSec)}")
        if (percent in 2..99) {
            line2.append(", осталось ≈ ${fmtTime(elapsedSec * (100 - percent) / percent)}")
        }

        ConversionState.status = "$line1\n$line2"
        ConversionState.progress = percent
        ConversionState.changed()
        KeepAliveService.update(this, line1, percent)
    }

    private fun runQueue() {
        val bitrate = bitrates[bitrateSpinner.selectedItemPosition]
        ConversionState.resetFailed()
        ConversionState.beginRun()
        ConversionState.progress = 0
        ConversionState.startedAt = System.currentTimeMillis()
        setBusy(true, "Подготовка…")
        requestNotificationPermission()
        KeepAliveService.start(this) // служба переднего плана: работа продолжится при выключенном экране

        worker.execute {
            var ok = 0
            var skipped = 0
            var failed = 0
            try {
                val existing = existingNames()

                while (true) {
                    val item = ConversionState.takeNext() ?: break // берёт файлы по порядку списка
                    val name = item.name
                    val base = name.substringBeforeLast('.')
                    report(item)

                    try {
                        if (SystemDecoder.isAmr(this, uri = item.uri)) {
                            // «AWB» на самом деле AMR-WB/AMR-NB: декодирует Android, кодирует LAME
                            val outName = "$base.mp3"
                            if (outName in existing) {
                                item.state = ItemState.SKIPPED
                                item.message = "уже есть в папке, пропущен"
                                skipped++
                            } else {
                                val size = convertAmr(item.uri, outName, bitrate) { done, all ->
                                    report(item, done, all)
                                }
                                existing.add(outName)
                                item.percent = 100
                                item.state = ItemState.DONE
                                item.message = "готово · ${fmtSize(size)}"
                                ok++
                            }
                        } else {
                            val result = convertGame(item, base, bitrate, existing)
                            ok += result.ok
                            skipped += result.skipped
                            failed += result.failed
                        }
                    } catch (e: Exception) {
                        item.state = ItemState.FAILED
                        item.message = e.message ?: "ошибка"
                        failed++
                    }
                    ConversionState.changed()
                }
            } catch (e: Exception) {
                failed++
                ConversionState.status = "Ошибка: ${e.message}"
            } finally {
                val summary = "Сохранено: $ok, пропущено: $skipped, ошибок: $failed. Папка: ${saveLabel()}"
                KeepAliveService.stop(this)
                KeepAliveService.showDone(this, summary)
                ConversionState.progress = 100
                setBusy(false, "Готово. $summary")
            }
        }
    }

    private class GameResult(val ok: Int, val skipped: Int, val failed: Int)

    /** Игровой AWB (AFS2 и др.): декодирует vgmstream; каждый трек внутри файла сохраняется отдельным MP3 */
    private fun convertGame(
        item: QueueItem, base: String, bitrate: Int, existing: MutableSet<String>
    ): GameResult {
        val input = File(cacheDir, "in.awb")
        var ok = 0
        var skipped = 0
        var failed = 0
        var totalSize = 0L
        var firstError = ""
        try {
            contentResolver.openInputStream(item.uri)!!.use { src ->
                input.outputStream().use { dst -> src.copyTo(dst) }
            }
            val count = converter.count(input.absolutePath)
            if (count < 0) {
                item.state = ItemState.FAILED
                item.message = "формат не распознан\n${converter.diagnose(input.absolutePath)}"
                return GameResult(0, 0, 1)
            }

            for (s in 1..count) {
                item.percent = ((s - 1) * 100) / count
                item.message = "трек $s из $count"
                ConversionState.changed()

                val outName = if (count == 1) "$base.mp3" else "${base}_${s.toString().padStart(3, '0')}.mp3"
                if (outName in existing) {
                    skipped++
                    continue
                }
                val tmp = File(cacheDir, "out.mp3")
                try {
                    val error = converter.convert(
                        input.absolutePath, if (count == 1) 0 else s, tmp.absolutePath, bitrate
                    )
                    if (error == null) {
                        totalSize += saveFile(tmp, outName)
                        existing.add(outName)
                        ok++
                    } else {
                        failed++
                        if (firstError.isEmpty()) firstError = error
                    }
                } finally {
                    tmp.delete()
                }
            }
        } finally {
            input.delete()
        }

        item.percent = 100
        when {
            failed > 0 -> {
                item.state = ItemState.FAILED
                item.message = "ошибок: $failed из ${ok + skipped + failed}. $firstError"
            }
            ok == 0 -> {
                item.state = ItemState.SKIPPED
                item.message = "уже есть в папке, пропущен"
            }
            else -> {
                item.state = ItemState.DONE
                item.message = "готово · треков: $ok · ${fmtSize(totalSize)}"
            }
        }
        return GameResult(if (ok > 0) 1 else 0, if (ok == 0 && failed == 0) 1 else 0, if (failed > 0) 1 else 0)
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
    private fun convertAmr(uri: Uri, outName: String, bitrate: Int, onProgress: (Long, Long) -> Unit): Long {
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
            return saveFile(tmp, outName)
        } finally {
            if (handle != 0L) converter.encoderClose(handle)
            tmp.delete()
        }
    }

    /** Сохраняет MP3 в выбранную папку или (если не выбрана) в Музыка/AWB2MP3 */
    private fun saveFile(file: File, displayName: String): Long {
        val size = file.length()
        val tree = saveTree
        if (tree == null) {
            saveToMusic(file, displayName)
            return size
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
        return size
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
