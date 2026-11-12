package cn.musicpilgrimage.app.performance

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

internal object OpusPcmDecoder {
    const val SAMPLE_RATE = 48000
    private const val CHANNELS = 2
    private const val MIME = "audio/opus"
    private const val OUTPUT_WAIT_US = 2_000L
    private const val STALL_TIMEOUT_MS = 5_000L
    private const val MAX_END_PADDING_FRAMES = 960

    fun decode(file: File, frames: Int): FloatArray {
        val extractor = MediaExtractor()
        try {
            file.inputStream().use { extractor.setDataSource(it.fd) }
            require(extractor.trackCount == 1) { "演奏音源格式错误" }
            val format = extractor.getTrackFormat(0)
            require(format.getString(MediaFormat.KEY_MIME) == MIME) { "演奏音源格式错误" }
            extractor.selectTrack(0)
            val codec = MediaCodec.createDecoderByType(MIME)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return readPcm(extractor, codec, frames)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun readPcm(extractor: MediaExtractor, codec: MediaCodec, frames: Int): FloatArray {
        val pcm = FloatArray(frames * CHANNELS)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var decodedSamples = 0
        var lastProgress = SystemClock.elapsedRealtime()
        while (!outputEnded) {
            while (!inputEnded) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) break
                val buffer = checkNotNull(codec.getInputBuffer(index))
                val size = extractor.readSampleData(buffer, 0)
                inputEnded = size < 0
                if (inputEnded) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                lastProgress = SystemClock.elapsedRealtime()
            }
            val index = codec.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT
                require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == SAMPLE_RATE &&
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == CHANNELS && encoding == AudioFormat.ENCODING_PCM_16BIT
                ) { "演奏音源解码格式错误" }
            } else if (index >= 0) {
                if (info.size > 0) {
                    require(info.size % (Short.SIZE_BYTES * CHANNELS) == 0)
                    val buffer = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val count = info.size / Short.SIZE_BYTES
                    val copy = minOf(count, (pcm.size - decodedSamples).coerceAtLeast(0))
                    repeat(copy) { pcm[decodedSamples + it] = buffer.short / 32768f }
                    decodedSamples += count
                    require(decodedSamples <= pcm.size + MAX_END_PADDING_FRAMES * CHANNELS) { "歌曲音频与谱面时长不一致" }
                }
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                lastProgress = SystemClock.elapsedRealtime()
            }
            check(SystemClock.elapsedRealtime() - lastProgress < STALL_TIMEOUT_MS) { "歌曲音频解码超时" }
        }
        // The codec applies the Ogg Opus pre-skip. Only final packet padding can be discarded here.
        require(decodedSamples >= pcm.size) { "歌曲音频与谱面时长不一致" }
        return pcm
    }
}
