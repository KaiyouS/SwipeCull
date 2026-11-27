package com.example.swipecull

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes a video's audio track to 16 kHz mono floats (what Whisper wants), a chunk at a time. */
class AudioDecoder(private val context: Context, private val uri: Uri) {

    /**
     * Calls [onChunk] with (start time in ms, samples) for each [chunkSec] of audio.
     * Returns false if the file has no audio track.
     */
    fun decode(chunkSec: Int, isActive: () -> Boolean, onChunk: (Long, FloatArray) -> Unit): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var pcmFloat = false
            var mono = FloatArray(chunkSec * rate)
            var filled = 0
            var emittedSource = 0L // source samples already handed out

            fun flush() {
                if (filled < rate / 2) { filled = 0; return } // ignore a trailing fraction of a second
                val startMs = emittedSource * 1000 / rate
                onChunk(startMs, resample(mono, filled, rate))
                emittedSource += filled
                filled = 0
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && isActive()) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!
                        val size = ex.readSampleData(ib, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (newRate != rate) {
                        flush()
                        rate = newRate
                        mono = FloatArray(chunkSec * rate)
                    }
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        if (pcmFloat) {
                            val fb = ob.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += fb.get() }
                                mono[filled++] = sum / channels
                                if (filled == mono.size) flush()
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var sum = 0
                                repeat(channels) { sum += sb.get().toInt() }
                                mono[filled++] = sum / (32768f * channels)
                                if (filled == mono.size) flush()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (isActive()) flush()
            return true
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /** Box-filter resample from [rate] to 16 kHz. */
    private fun resample(src: FloatArray, n: Int, rate: Int): FloatArray {
        if (rate == 16000) return src.copyOf(n)
        val outLen = (n.toLong() * 16000 / rate).toInt()
        val out = FloatArray(outLen)
        val ratio = rate / 16000.0
        for (i in 0 until outLen) {
            val a = (i * ratio).toInt().coerceAtMost(n - 1)
            val b = ((i + 1) * ratio).toInt().coerceIn(a + 1, n)
            var sum = 0f
            for (k in a until b) sum += src[k]
            out[i] = sum / (b - a)
        }
        return out
    }
}
