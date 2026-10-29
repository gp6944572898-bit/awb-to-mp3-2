package com.awb2mp3.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.FileDescriptor

/** Приёмник декодированного звука (PCM 16 бит) */
interface PcmSink {
    /** Вызывается один раз перед первой порцией данных */
    fun start(sampleRate: Int, channels: Int)

    fun write(data: ByteArray, size: Int)
}

/**
 * Декодирование средствами самого Android (MediaExtractor + MediaCodec).
 * Нужно для файлов с расширением .awb, которые на самом деле AMR-WB (речь, аудиокниги),
 * а не игровой AWB — vgmstream их не поддерживает.
 * Звук отдаётся порциями, поэтому длинные файлы не требуют много памяти и места.
 */
object SystemDecoder {

    /** AMR-файлы (AMR-NB и AMR-WB) начинаются с текстовой сигнатуры "#!AMR" */
    fun isAmr(context: Context, uri: Uri): Boolean {
        val stream = context.contentResolver.openInputStream(uri) ?: return false
        stream.use {
            val head = ByteArray(5)
            var got = 0
            while (got < head.size) {
                val n = it.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            return got == 5 && String(head, Charsets.ISO_8859_1) == "#!AMR"
        }
    }

    /**
     * Декодирует файл из [fd] и отдаёт PCM в [sink].
     * [onProgress] получает долю 0..1 (примерно раз в секунду), если длительность известна.
     */
    fun decode(fd: FileDescriptor, sink: PcmSink, onProgress: (Double) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(fd)

            var track = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    trackFormat = f
                    break
                }
            }
            val format = trackFormat ?: throw IllegalStateException("в файле нет аудиодорожки")
            extractor.selectTrack(track)

            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var started = false
            var lastReport = 0L
            var chunk = ByteArray(32 * 1024)
            var idleAfterInput = 0 // сколько раз подряд декодер ничего не вернул после конца входа

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            if (!started) {
                                sink.start(rate, channels)
                                started = true
                            }
                            val outBuf = decoder.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (chunk.size < info.size) chunk = ByteArray(info.size)
                            outBuf.get(chunk, 0, info.size)
                            sink.write(chunk, info.size)

                            val now = System.currentTimeMillis()
                            if (durationUs > 0 && now - lastReport > 1000) {
                                lastReport = now
                                onProgress((info.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0))
                            }
                        }
                        idleAfterInput = 0
                        decoder.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // некоторые декодеры не присылают флаг конца потока — не ждём вечно
                        if (inputDone && ++idleAfterInput > 500) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = decoder.outputFormat
                        val newRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (started && (newRate != rate || newChannels != channels)) {
                            throw IllegalStateException("формат звука изменился посреди файла")
                        }
                        rate = newRate
                        channels = newChannels
                    }
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // кодек мог уже остановиться
            }
            codec?.release()
            extractor.release()
        }
    }
}
