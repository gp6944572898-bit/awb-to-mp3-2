package com.awb2mp3.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream

/**
 * Декодирование средствами самого Android (MediaExtractor + MediaCodec).
 * Нужно для файлов с расширением .awb, которые на самом деле AMR-WB (речь, рингтоны),
 * а не игровой AWB — vgmstream их не поддерживает.
 */
object SystemDecoder {

    class Result(val sampleRate: Int, val channels: Int)

    /** AMR-файлы (AMR-NB и AMR-WB) начинаются с текстовой сигнатуры "#!AMR" */
    fun isAmr(path: String): Boolean {
        File(path).inputStream().use { stream ->
            val head = ByteArray(5)
            val n = stream.read(head)
            return n == 5 && String(head, Charsets.ISO_8859_1) == "#!AMR"
        }
    }

    /** Декодирует звук в сырой PCM16 и возвращает частоту и число каналов */
    fun decodeToPcm(inPath: String, outPcm: File): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(inPath)

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

            FileOutputStream(outPcm).use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
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
                                val outBuf = decoder.getOutputBuffer(outIndex)!!
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                outBuf.get(chunk)
                                out.write(chunk)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = decoder.outputFormat
                            rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }
            return Result(rate, channels)
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
