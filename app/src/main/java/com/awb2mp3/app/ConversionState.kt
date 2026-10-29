package com.awb2mp3.app

/**
 * Состояние конвертации живёт в процессе, а не в экране: если Android пересоздаёт
 * Activity (или пользователь вернулся из уведомления), экран заново показывает
 * текущий статус, прогресс и журнал.
 */
object ConversionState {
    @Volatile var busy: Boolean = false
    @Volatile var status: String = "Выберите файлы"
    @Volatile var progress: Int = 0

    /** Момент старта текущей конвертации (мс) */
    @Volatile var startedAt: Long = 0L

    private val logBuilder = StringBuilder()

    /** Подписчик (текущий экран); вызывается из любого потока */
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun logText(): String = logBuilder.toString()

    @Synchronized
    fun clearLog() {
        logBuilder.setLength(0)
    }

    @Synchronized
    fun addLog(line: String) {
        logBuilder.append(line).append('\n')
    }

    fun changed() {
        listener?.invoke()
    }
}
