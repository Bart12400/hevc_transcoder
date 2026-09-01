package com.example.hevc_transcoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import kotlin.math.pow

class TranscoderEngine(
    private val inputFile: File,
    private val outputFile: File,
    private val onProgress: (progress: Float, speed: Float, etaSeconds: Long) -> Unit
) {
    @Volatile private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    fun start(): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
        } catch (e: Exception) {
            return false
        }

        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i
                videoFormat = format
                break
            }
        }

        if (videoTrackIndex == -1 || videoFormat == null) {
            extractor.release()
            return false
        }

        val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else 1L

        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val targetBitrate = (width.toDouble() * height.toDouble()).pow(0.75).toInt() * 50

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        encoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(videoFormat, inputSurface, null, 0)
        decoder.start()

        extractor.selectTrack(videoTrackIndex)

        var muxerVideoTrackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val startTimeMs = System.currentTimeMillis()

        var isExtractorEOS = false
        var isDecoderEOS = false
        var isEncoderEOS = false

        while (!isEncoderEOS && !isCancelled) {
            if (!isExtractorEOS) {
                val inputBufIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isExtractorEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            if (!isDecoderEOS) {
                val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (decoderStatus >= 0) {
                    val doRender = bufferInfo.size != 0
                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream()
                        isDecoderEOS = true
                    }
                }
            }

            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxerVideoTrackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                muxerStarted = true
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)
                if (encodedData != null && muxerStarted && bufferInfo.size > 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(muxerVideoTrackIndex, encodedData, bufferInfo)

                    val currentPresentationTimeUs = bufferInfo.presentationTimeUs
                    val progress = (currentPresentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                    val elapsedTimeSec = (System.currentTimeMillis() - startTimeMs) / 1000f
                    val processedVideoSec = currentPresentationTimeUs / 1_000_000f

                    val speed = if (elapsedTimeSec > 0) processedVideoSec / elapsedTimeSec else 0f
                    val remainingVideoSec = (durationUs - currentPresentationTimeUs) / 1_000_000f
                    val etaSeconds = if (speed > 0) (remainingVideoSec / speed).toLong() else 0L

                    onProgress(progress, speed, etaSeconds)
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isEncoderEOS = true
                }
            }
        }

        try {
            decoder.stop(); decoder.release()
            encoder.stop(); encoder.release()
            extractor.release()
            if (muxerStarted) { muxer.stop(); muxer.release() }
        } catch (_: Exception) {}

        return !isCancelled && isEncoderEOS
    }
}
