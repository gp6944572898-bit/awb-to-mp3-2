package com.awb2mp3.app

import android.net.Uri

enum class ItemState { PENDING, RUNNING, DONE, SKIPPED, FAILED }

/** Файл в очереди конвертации */
class QueueItem(val uri: Uri, val name: String) {
    @Volatile var state: ItemState = ItemState.PENDING
    @Volatile var percent: Int = 0
    @Volatile var message: String = ""

    /** Участвует в текущем запуске (для расчёта общего прогресса) */
    @Volatile var inRun: Boolean = false
}

/**
 * Состояние живёт в процессе, а не в экране: если Android пересоздаёт Activity
 * (или пользователь вернулся из уведомления), экран заново показывает очередь,
 * статус и прогресс.
 */
object ConversionState {
    @Volatile var busy: Boolean = false
    @Volatile var status: String = "Добавьте файлы AWB"
    @Volatile var progress: Int = 0

    /** Момент старта текущего запуска (мс) */
    @Volatile var startedAt: Long = 0L

    /** Подписчик (текущий экран); вызывается из любого потока */
    @Volatile var listener: (() -> Unit)? = null

    private val items = ArrayList<QueueItem>()

    @Synchronized
    fun snapshot(): List<QueueItem> = ArrayList(items)

    /** Добавляет файлы (без повторов) и держит список отсортированным по имени. Возвращает, сколько добавлено. */
    @Synchronized
    fun add(newItems: List<QueueItem>): Int {
        val known = items.map { it.uri.toString() }.toHashSet()
        var added = 0
        for (item in newItems) {
            if (known.add(item.uri.toString())) {
                items.add(item)
                added++
            }
        }
        items.sortBy { it.name.lowercase() }
        return added
    }

    /** Удаляет файл из очереди; файл, который конвертируется прямо сейчас, удалить нельзя */
    @Synchronized
    fun remove(item: QueueItem): Boolean {
        if (item.state == ItemState.RUNNING) return false
        return items.remove(item)
    }

    @Synchronized
    fun clearAll() {
        items.removeAll { it.state != ItemState.RUNNING }
    }

    /** Неудавшиеся файлы возвращаем в очередь для повторной попытки */
    @Synchronized
    fun resetFailed() {
        for (item in items) {
            if (item.state == ItemState.FAILED) {
                item.state = ItemState.PENDING
                item.percent = 0
                item.message = ""
            }
        }
    }

    @Synchronized
    fun pendingCount(): Int = items.count { it.state == ItemState.PENDING || it.state == ItemState.FAILED }

    /** Начало запуска: в расчёт общего прогресса берём только ожидающие файлы */
    @Synchronized
    fun beginRun() {
        for (item in items) item.inRun = item.state == ItemState.PENDING
    }

    /** Берёт следующий ожидающий файл и помечает его как «идёт конвертация» */
    @Synchronized
    fun takeNext(): QueueItem? {
        val next = items.firstOrNull { it.state == ItemState.PENDING } ?: return null
        next.state = ItemState.RUNNING
        next.percent = 0
        next.message = ""
        next.inRun = true
        return next
    }

    /** (обработано, всего) среди файлов текущего запуска */
    @Synchronized
    fun runProgress(): Pair<Int, Int> {
        val run = items.filter { it.inRun }
        val finished = run.count {
            it.state == ItemState.DONE || it.state == ItemState.SKIPPED || it.state == ItemState.FAILED
        }
        return finished to run.size
    }

    /** Общий процент текущего запуска с учётом доли конвертируемого сейчас файла */
    @Synchronized
    fun overallPercent(): Int {
        val run = items.filter { it.inRun }
        if (run.isEmpty()) return 0
        val sum = run.sumOf {
            when (it.state) {
                ItemState.PENDING -> 0.0
                ItemState.RUNNING -> it.percent / 100.0
                else -> 1.0
            }
        }
        return ((sum / run.size) * 100).toInt().coerceIn(0, 100)
    }

    fun changed() {
        listener?.invoke()
    }
}
