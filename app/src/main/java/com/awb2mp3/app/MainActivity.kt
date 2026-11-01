package com.awb2mp3.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

// ---------- палитра в цветах иконки ----------
private val C_BG1 = Color.parseColor("#041448")
private val C_BG2 = Color.parseColor("#05366E")
private val C_BG3 = Color.parseColor("#037E78")
private val C_BLUE = Color.parseColor("#2F6BFF")
private val C_CYAN = Color.parseColor("#22E5F0")
private val C_GREEN = Color.parseColor("#3DEB9A")
private val C_RED = Color.parseColor("#FF7A7A")
private val C_TEXT2 = Color.parseColor("#B9C8E4")
private val C_GLASS = Color.parseColor("#1FFFFFFF")
private val C_GLASS_STROKE = Color.parseColor("#33FFFFFF")
private val C_POPUP = Color.parseColor("#0B2A63")

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Цвет с заданной прозрачностью (alpha 0..255) */
private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

class MainActivity : Activity() {

    private val converter by lazy { NativeConverter() }
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var saveRow: LinearLayout
    private lateinit var saveValue: TextView
    private lateinit var btnFiles: Button
    private lateinit var btnFolder: Button
    private lateinit var btnStart: Button
    private lateinit var btnClear: Button
    private lateinit var bitrateSpinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var percentText: TextView
    private lateinit var list: ListView
    private val adapter = QueueAdapter()

    /** Пульсация свечения кнопки «Начать», пока её можно нажать */
    private var startPulse: ObjectAnimator? = null

    /** Варианты битрейта; 0 = «Авто»: для речи (AMR) как у исходного файла, для музыки 192 */
    private val bitrates = listOf(0, 16, 24, 32, 48, 64, 96, 128, 192, 256, 320)

    /** Выбранная папка сохранения (null = Музыка/AWB2MP3 по умолчанию) */
    private var saveTree: Uri? = null

    /** Пользователь ушёл выбирать другую папку из окна старта — после выбора окно покажем снова */
    private var resumeStartDialog = false

    private val prefs: SharedPreferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // фон окна — диагональный градиент из цветов иконки
        window.setBackgroundDrawable(
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C_BG1, C_BG2, C_BG3))
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

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
        updateStartPulse(false)
        super.onPause()
    }

    /** Отображает очередь, статус, общий прогресс и доступность кнопок */
    private fun render() {
        val busy = ConversionState.busy
        val items = ConversionState.snapshot()
        val pending = items.count { it.state == ItemState.PENDING || it.state == ItemState.FAILED }
        val canStart = !busy && pending > 0

        setActive(btnFiles, !busy)
        setActive(btnFolder, !busy)
        setActive(saveRow, !busy)
        bitrateSpinner.isEnabled = !busy
        bitrateSpinner.alpha = if (busy) 0.5f else 1f
        setActive(btnStart, canStart)
        btnStart.text = when {
            busy -> "Идёт конвертация…"
            pending > 0 -> "Начать конвертацию ($pending)"
            else -> "Начать конвертацию"
        }
        setActive(btnClear, !busy && items.isNotEmpty())
        updateStartPulse(canStart)

        status.text = ConversionState.status
        val p = ConversionState.progress
        percentText.text = if (busy || p in 1..99) "$p%" else ""
        progress.setProgress(p, true)
        adapter.submit(items)
    }

    private fun setActive(v: View, active: Boolean) {
        v.isEnabled = active
        v.alpha = if (active) 1f else 0.45f
    }

    /** Мягкая пульсация тени-подсветки у главной кнопки */
    private fun updateStartPulse(active: Boolean) {
        if (active) {
            if (startPulse == null) {
                startPulse = ObjectAnimator.ofFloat(btnStart, View.TRANSLATION_Z, 0f, dpf(8f)).apply {
                    duration = 1100
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            startPulse?.cancel()
            startPulse = null
            btnStart.translationZ = 0f
        }
    }

    // ---------- вспомогательные функции оформления ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun lp(
        w: Int, h: Int, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, weight: Float = 0f
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight).apply {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
    }

    private fun shape(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp)
            if (strokeDp > 0f) setStroke(dp(strokeDp.toInt().coerceAtLeast(1)), strokeColor)
        }

    /** Волна-подсветка при нажатии поверх любого фона */
    private fun ripple(content: Drawable?, radiusDp: Float, oval: Boolean = false): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            if (oval) this.shape = GradientDrawable.OVAL else cornerRadius = dpf(radiusDp)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), content, mask)
    }

    /** Лёгкое «вдавливание» при нажатии */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressEffect() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }

    private fun styledButton(label: String, primary: Boolean): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), dp(13), dp(14), dp(13))
        val face: Drawable = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_BLUE, C_CYAN, C_GREEN))
                .apply { cornerRadius = dpf(16f) }
        } else {
            shape(C_GLASS, 16f, C_GLASS_STROKE, 1f)
        }
        background = ripple(face, 16f)
        if (primary) {
            elevation = dpf(10f)
            outlineSpotShadowColor = C_CYAN // цветная подсветка вокруг кнопки
            outlineAmbientShadowColor = C_CYAN
        }
        pressEffect()
    }

    private fun styledProgress(heightDp: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val track = shape(Color.parseColor("#26FFFFFF"), heightDp / 2f)
            val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_BLUE, C_CYAN, C_GREEN))
                .apply { cornerRadius = dpf(heightDp / 2f) }
            progressDrawable = LayerDrawable(arrayOf<Drawable>(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)))
                .apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
        }

    private inner class DarkSpinnerAdapter(items: List<String>) :
        ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
        init {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        // отступы под системные панели (при сквозном режиме экрана)
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                dp(14) + insets.systemWindowInsetLeft,
                dp(10) + insets.systemWindowInsetTop,
                dp(14) + insets.systemWindowInsetRight,
                dp(8) + insets.systemWindowInsetBottom
            )
            insets
        }

        // --- заголовок с градиентом ---
        val title = TextView(this).apply {
            text = "AWB → MP3"
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        title.post {
            title.paint.shader = LinearGradient(
                0f, 0f, title.paint.measureText(title.text.toString()), 0f,
                intArrayOf(Color.WHITE, C_CYAN, C_GREEN), null, Shader.TileMode.CLAMP
            )
            title.invalidate()
        }
        root.addView(title, lp(WRAP, WRAP))
        root.addView(TextView(this).apply {
            text = "Конвертер аудиокниг"
            textSize = 13f
            setTextColor(C_TEXT2)
        }, lp(WRAP, WRAP, bottom = 10))

        // --- карточка настроек ---
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(C_GLASS, 22f, C_GLASS_STROKE, 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        saveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ripple(shape(C_GLASS, 16f, C_GLASS_STROKE, 1f), 16f)
            isClickable = true
            setOnClickListener { pickSaveFolder() }
            pressEffect()
        }
        saveRow.addView(TextView(this).apply {
            text = "📁"
            textSize = 22f
        }, lp(WRAP, WRAP, end = 10))
        val saveText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saveText.addView(TextView(this).apply {
            text = "Сохранять в"
            textSize = 12f
            setTextColor(C_TEXT2)
        })
        saveValue = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        saveText.addView(saveValue)
        saveRow.addView(saveText, lp(0, WRAP, weight = 1f))
        saveRow.addView(TextView(this).apply {
            text = "›"
            textSize = 26f
            setTextColor(C_TEXT2)
        })
        controls.addView(saveRow, lp(MATCH, WRAP))

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnFiles = styledButton("＋ Файлы", false).apply { setOnClickListener { pickFiles() } }
        btnFolder = styledButton("＋ Папка", false).apply { setOnClickListener { pickFolder() } }
        addRow.addView(btnFiles, lp(0, WRAP, end = 4, weight = 1f))
        addRow.addView(btnFolder, lp(0, WRAP, start = 4, weight = 1f))
        controls.addView(addRow, lp(MATCH, WRAP, top = 8))

        val bitrateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bitrateRow.addView(TextView(this).apply {
            text = "Битрейт, кбит/с"
            textSize = 14f
            setTextColor(C_TEXT2)
        }, lp(WRAP, WRAP, end = 8))
        bitrateSpinner = Spinner(this).apply {
            adapter = DarkSpinnerAdapter(bitrates.map { if (it == 0) "Авто (как у исходника)" else it.toString() })
            setSelection(0)
            setPopupBackgroundDrawable(shape(C_POPUP, 14f, C_GLASS_STROKE, 1f))
        }
        bitrateRow.addView(bitrateSpinner, lp(0, WRAP, weight = 1f))
        controls.addView(bitrateRow, lp(MATCH, WRAP, top = 4))
        root.addView(controls, lp(MATCH, WRAP))

        // --- кнопки запуска ---
        val startRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = styledButton("Начать конвертацию", true).apply {
            textSize = 16f
            setOnClickListener { onStartClicked() }
        }
        btnClear = styledButton("Очистить", false).apply {
            setOnClickListener {
                ConversionState.clearAll()
                ConversionState.progress = 0
                ConversionState.status = "Добавьте файлы AWB"
                ConversionState.changed()
            }
        }
        startRow.addView(btnStart, lp(0, WRAP, end = 6, weight = 2f))
        startRow.addView(btnClear, lp(0, WRAP, start = 6, weight = 1f))
        root.addView(startRow, lp(MATCH, WRAP, top = 12))

        // --- карточка общего прогресса ---
        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(C_GLASS, 20f, C_GLASS_STROKE, 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        percentText = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(C_CYAN)
        }
        statusRow.addView(status, lp(0, WRAP, end = 8, weight = 1f))
        statusRow.addView(percentText, lp(WRAP, WRAP))
        progressCard.addView(statusRow, lp(MATCH, WRAP))
        progress = styledProgress(8)
        progressCard.addView(progress, lp(MATCH, dp(8), top = 10))
        root.addView(progressCard, lp(MATCH, WRAP, top = 12))

        // --- список файлов ---
        val frame = FrameLayout(this).apply { clipChildren = false }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            dividerHeight = 0
            clipToPadding = false
            clipChildren = false
            setPadding(0, dp(4), 0, dp(8))
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        val empty = TextView(this).apply {
            text = "Список пуст\nДобавьте файлы или папку с AWB"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(C_TEXT2)
        }
        frame.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        list.emptyView = empty
        root.addView(frame, lp(MATCH, 0, top = 8, weight = 1f))

        setContentView(root)
        root.requestApplyInsets()
    }

    // ---------- список файлов ----------

    private inner class RowHolder {
        lateinit var wrapper: FrameLayout
        lateinit var card: LinearLayout
        lateinit var cardBg: GradientDrawable
        lateinit var icon: TextView
        lateinit var iconBg: GradientDrawable
        lateinit var name: TextView
        lateinit var bar: ProgressBar
        lateinit var message: TextView
        lateinit var close: TextView

        var item: QueueItem? = null
        private var lastState: ItemState? = null
        private var pulse: ObjectAnimator? = null
        private var glow: ValueAnimator? = null

        fun build(): View {
            wrapper = FrameLayout(this@MainActivity).apply {
                setPadding(dp(1), dp(4), dp(1), dp(4))
                clipChildren = false
                clipToPadding = false
            }
            cardBg = shape(C_GLASS, 18f, C_GLASS_STROKE, 1f)
            card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(4), dp(10))
                background = cardBg
                elevation = dpf(2f)
            }

            iconBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            icon = TextView(this@MainActivity).apply {
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = iconBg
            }
            card.addView(icon, lp(dp(38), dp(38), end = 12))

            val middle = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            name = TextView(this@MainActivity).apply {
                textSize = 15f
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            bar = styledProgress(6)
            message = TextView(this@MainActivity).apply { textSize = 12f }
            middle.addView(name, lp(MATCH, WRAP))
            middle.addView(bar, lp(MATCH, dp(6), top = 6))
            middle.addView(message, lp(MATCH, WRAP, top = 4))
            card.addView(middle, lp(0, WRAP, weight = 1f))

            close = TextView(this@MainActivity).apply {
                text = "✕"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(C_TEXT2)
                background = ripple(null, 20f, oval = true)
                isClickable = true
                contentDescription = "Убрать из списка"
                setOnClickListener {
                    val queueItem = item ?: return@setOnClickListener
                    if (queueItem.state == ItemState.RUNNING) return@setOnClickListener
                    // уезжает вправо и растворяется, затем удаляется из списка
                    wrapper.animate().alpha(0f).translationX(dpf(60f)).setDuration(180).withEndAction {
                        if (ConversionState.remove(queueItem)) ConversionState.changed()
                    }.start()
                }
            }
            card.addView(close, lp(dp(42), dp(42)))

            wrapper.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
            return wrapper
        }

        private fun stopAnimations() {
            pulse?.cancel()
            pulse = null
            glow?.cancel()
            glow = null
            icon.animate().cancel()
            icon.scaleX = 1f
            icon.scaleY = 1f
        }

        fun bind(data: QueueItem) {
            val changedItem = item !== data
            val wasState = lastState
            val stateChanged = changedItem || wasState != data.state
            item = data
            lastState = data.state

            // динамические части обновляем всегда
            name.text = data.name
            val running = data.state == ItemState.RUNNING
            bar.visibility = if (running) View.VISIBLE else View.GONE
            bar.setProgress(data.percent, !changedItem)
            val msg = if (running && data.percent > 0) "${data.percent}%  ${data.message}".trim() else data.message
            message.text = msg
            message.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
            message.setTextColor(if (data.state == ItemState.FAILED) C_RED else C_TEXT2)
            close.visibility = if (running) View.INVISIBLE else View.VISIBLE

            if (!stateChanged) return // иначе анимации «дёргались» бы при каждом обновлении
            stopAnimations()

            val (glyph, color) = when (data.state) {
                ItemState.PENDING -> "○" to C_TEXT2
                ItemState.RUNNING -> "▶" to C_CYAN
                ItemState.DONE -> "✓" to C_GREEN
                ItemState.SKIPPED -> "↷" to C_TEXT2
                ItemState.FAILED -> "!" to C_RED
            }
            icon.text = glyph
            icon.setTextColor(color)
            iconBg.setColor(withAlpha(color, 0x38))

            if (running) {
                // подсветка бегущего файла: цветная тень, мерцающая рамка и пульсирующая иконка
                card.elevation = dpf(10f)
                card.outlineSpotShadowColor = C_CYAN
                card.outlineAmbientShadowColor = C_CYAN
                glow = ValueAnimator.ofInt(60, 255).apply {
                    duration = 900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { cardBg.setStroke(dp(2), withAlpha(C_CYAN, it.animatedValue as Int)) }
                    start()
                }
                pulse = ObjectAnimator.ofPropertyValuesHolder(
                    icon,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f)
                ).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else {
                card.elevation = dpf(2f)
                card.outlineSpotShadowColor = Color.BLACK
                card.outlineAmbientShadowColor = Color.BLACK
                val stroke = when (data.state) {
                    ItemState.DONE -> withAlpha(C_GREEN, 0x70)
                    ItemState.FAILED -> withAlpha(C_RED, 0x70)
                    else -> C_GLASS_STROKE
                }
                cardBg.setStroke(dp(1), stroke)
            }

            // «хлопок» галочки в момент завершения файла
            if (!changedItem && wasState != null && wasState != ItemState.DONE && data.state == ItemState.DONE) {
                icon.scaleX = 0.2f
                icon.scaleY = 0.2f
                icon.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(2.5f)).start()
            }
        }
    }

    private inner class QueueAdapter : BaseAdapter() {
        private var data: List<QueueItem> = emptyList()
        private val seen = HashSet<String>()

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
            val queueItem = data[position]
            val firstTime = seen.add(queueItem.uri.toString())

            if (holder.item !== queueItem) {
                // строка переиспользована для другого файла — сбрасываем остатки анимации
                view.animate().cancel()
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
            }
            holder.bind(queueItem)

            if (firstTime) {
                // новая строка плавно «выезжает» снизу
                view.alpha = 0f
                view.translationY = dpf(16f)
                view.animate().alpha(1f).translationY(0f)
                    .setStartDelay(minOf(position, 8) * 35L).setDuration(300)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
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
        saveValue.text = saveLabel()
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
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
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
