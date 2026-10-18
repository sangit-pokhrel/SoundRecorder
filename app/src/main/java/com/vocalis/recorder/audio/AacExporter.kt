package com.vocalis.recorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Encodes a WAV recording to AAC-LC in an .m4a container, ready for upload or sharing. */
object AacExporter {

    fun export(input: File, output: File, bitRate: Int = 192_000, onProgress: (Float) -> Unit = {}) {
        WavReader(input).use { reader ->
            val channels = reader.channels
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, reader.sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val floats = FloatArray(4096 * channels)
                val total = reader.totalFrames.coerceAtLeast(1)
                var framesQueued = 0L
                var track = -1
                var aacFramesWritten = 0L
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!.order(ByteOrder.nativeOrder())
                            buf.clear()
                            val maxFrames = minOf(buf.capacity() / (2 * channels), 4096)
                            val n = reader.read(floats, maxFrames)
                            val pts = framesQueued * 1_000_000L / reader.sampleRate
                            if (n == 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                for (i in 0 until n * channels) {
                                    buf.putShort((floats[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                                }
                                codec.queueInputBuffer(inIndex, 0, n * channels * 2, pts, 0)
                                framesQueued += n
                                onProgress(framesQueued.toFloat() / total)
                            }
                        }
                    }

                    // Drain everything that's ready. Only block once all input has been queued,
                    // otherwise waiting here would throttle the encoder.
                    while (!outputDone) {
                        val outIndex = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0L)
                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        } else if (outIndex >= 0) {
                            val out = codec.getOutputBuffer(outIndex)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted) {
                                // Some encoders emit slightly non-monotonic timestamps, which the
                                // MP4 writer rejects. Every AAC-LC frame is exactly 1024 samples,
                                // so derive timestamps from the frame count instead.
                                info.presentationTimeUs = aacFramesWritten * AAC_FRAME_SAMPLES * 1_000_000L / reader.sampleRate
                                aacFramesWritten++
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                muxer.writeSampleData(track, out, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                if (muxerStarted) runCatching { muxer.stop() }
                muxer.release()
            }
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val AAC_FRAME_SAMPLES = 1024L
}
