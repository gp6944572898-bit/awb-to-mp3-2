package com.awb2mp3.app

/** Обёртка над нативной библиотекой (vgmstream для декодирования AWB + LAME для MP3) */
class NativeConverter {

    /** Число треков внутри файла (>= 1), отрицательное значение — файл не распознан */
    external fun count(path: String): Int

    /** Конвертирует трек [subsong] (с 1) в MP3. Возвращает null при успехе или текст ошибки */
    external fun convert(path: String, subsong: Int, out: String, bitrateKbps: Int): String?

    /** Потоковый MP3-кодировщик: открыть (0 = ошибка), записать порцию PCM16, закрыть */
    external fun encoderOpen(out: String, sampleRate: Int, channels: Int, bitrateKbps: Int): Long
    external fun encoderWrite(handle: Long, pcm: ByteArray, len: Int): Int
    external fun encoderClose(handle: Long): Int

    /** Диагностика файла, который не открылся: заголовок и сообщения декодера */
    external fun diagnose(path: String): String

    companion object {
        init {
            System.loadLibrary("awbconv")
        }
    }
}
