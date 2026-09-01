package com.example.hevc_transcoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
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
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i
                videoFormat = format
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i
                audioFormat = format
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
        val targetVideoBitrate = (width.toDouble() * height.toDouble()).pow(0.75).toInt() * 50

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // --- KONFIGURACJA WIDEO (HEVC) ---
        val outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetVideoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        videoEncoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = videoEncoder.createInputSurface()
        videoEncoder.start()

        val videoDecoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
        videoDecoder.configure(videoFormat, inputSurface, null, 0)
        videoDecoder.start()

        extractor.selectTrack(videoTrackIndex)

        // --- KONFIGURACJA AUDIO (ANALIZA 64 kb/s) ---
        var reencodeAudio = false
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxerAudioTrackIndex = -1

        if (audioTrackIndex != -1 && audioFormat != null) {
            extractor.selectTrack(audioTrackIndex)
            val currentAudioBitrate = if (audioFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } else 0

            if (currentAudioBitrate > 64_000) {
                reencodeAudio = true
                val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                val outputAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }

                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()

                audioDecoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME)!!)
                audioDecoder.configure(audioFormat, null, null, 0)
                audioDecoder.start()
            } else {
                muxerAudioTrackIndex = muxer.addTrack(audioFormat)
            }
        }

        var muxerVideoTrackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val startTimeMs = System.currentTimeMillis()

        var isExtractorVideoEOS = false
        var isDecoderVideoEOS = false
        var isEncoderVideoEOS = false

        var isExtractorAudioEOS = false
        var isDecoderAudioEOS = false
        var isEncoderAudioEOS = false

        val audioBuffer = ByteBuffer.allocateDirect(1024 * 1024)

        while ((!isEncoderVideoEOS || (reencodeAudio && !isEncoderAudioEOS)) && !isCancelled) {

            if (!isExtractorVideoEOS) {
                val inputBufIndex = videoDecoder.dequeueInputBuffer(1000)
                if (inputBufIndex >= 0) {
                    val inputBuffer = videoDecoder.getInputBuffer(inputBufIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0 || extractor.sampleTrackIndex != videoTrackIndex) {
                            if (extractor.sampleTrackIndex == videoTrackIndex || sampleSize < 0) {
                                videoDecoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorVideoEOS = true
                            }
                        } else {
                            val sampleTime = extractor.sampleTime
                            videoDecoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            if (!isDecoderVideoEOS) {
                val decoderStatus = videoDecoder.dequeueOutputBuffer(bufferInfo, 1000)
                if (decoderStatus >= 0) {
                    val doRender = bufferInfo.size != 0
                    videoDecoder.releaseOutputBuffer(decoderStatus, doRender)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        videoEncoder.signalEndOfInputStream()
                        isDecoderVideoEOS = true
                    }
                }
            }

            val encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, 1000)
            if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxerVideoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                if (!muxerStarted && (audioTrackIndex == -1 || !reencodeAudio || muxerAudioTrackIndex != -1)) {
                    muxer.start()
                    muxerStarted = true
                }
            } else if (encoderStatus >= 0) {
                val encodedData = videoEncoder.getOutputBuffer(encoderStatus)
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
                videoEncoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isEncoderVideoEOS = true
                }
            }

            if (audioTrackIndex != -1) {
                if (reencodeAudio) {
                    if (!isExtractorAudioEOS && audioDecoder != null) {
                        val inputIdx = audioDecoder.dequeueInputBuffer(1000)
                        if (inputIdx >= 0) {
                            val buf = audioDecoder.getInputBuffer(inputIdx)
                            if (buf != null) {
                                val size = extractor.readSampleData(buf, 0)
                                if (size < 0) {
                                    audioDecoder.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isExtractorAudioEOS = true
                                } else if (extractor.sampleTrackIndex == audioTrackIndex) {
                                    audioDecoder.queueInputBuffer(inputIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    if (!isDecoderAudioEOS && audioDecoder != null && audioEncoder != null) {
                        val decIdx = audioDecoder.dequeueOutputBuffer(bufferInfo, 1000)
                        if (decIdx >= 0) {
                            val decBuf = audioDecoder.getOutputBuffer(decIdx)
                            val encInputIdx = audioEncoder.dequeueInputBuffer(1000)
                            if (encInputIdx >= 0 && decBuf != null) {
                                val encBuf = audioEncoder.getInputBuffer(encInputIdx)
                                if (encBuf != null) {
                                    encBuf.put(decBuf)
                                    val flags = if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        isDecoderAudioEOS = true
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    } else 0
                                    audioEncoder.queueInputBuffer(encInputIdx, 0, bufferInfo.size, bufferInfo.presentationTimeUs, flags)
                                }
                            }
                            audioDecoder.releaseOutputBuffer(decIdx, false)
                        }
                    }

                    if (audioEncoder != null) {
                        val encIdx = audioEncoder.dequeueOutputBuffer(bufferInfo, 1000)
                        if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            muxerAudioTrackIndex = muxer.addTrack(audioEncoder.outputFormat)
                            if (!muxerStarted && muxerVideoTrackIndex != -1) {
                                muxer.start()
                                muxerStarted = true
                            }
                        } else if (encIdx >= 0) {
                            val encBuf = audioEncoder.getOutputBuffer(encIdx)
                            if (encBuf != null && muxerStarted && bufferInfo.size > 0) {
                                encBuf.position(bufferInfo.offset)
                                encBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerAudioTrackIndex, encBuf, bufferInfo)
                            }
                            audioEncoder.releaseOutputBuffer(encIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                isEncoderAudioEOS = true
                            }
                        }
                    }
                } else {
                    if (muxerStarted) {
                        audioBuffer.clear()
                        val sampleSize = extractor.readSampleData(audioBuffer, 0)
                        if (sampleSize >= 0 && extractor.sampleTrackIndex == audioTrackIndex) {
                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = extractor.sampleTime
                            bufferInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(muxerAudioTrackIndex, audioBuffer, bufferInfo)
                            extractor.advance()
                        }
                    }
                }
            }
        }

        try {
            videoDecoder.stop(); videoDecoder.release()
            videoEncoder.stop(); videoEncoder.release()
            audioDecoder?.stop(); audioDecoder?.release()
            audioEncoder?.stop(); audioEncoder?.release()
            extractor.release()
            if (muxerStarted) { muxer.stop(); muxer.release() }
        } catch (_: Exception) {}

        return !isCancelled && isEncoderVideoEOS
    }
}
