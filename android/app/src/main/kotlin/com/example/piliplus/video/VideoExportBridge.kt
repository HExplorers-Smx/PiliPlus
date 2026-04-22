package com.example.piliplus.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max

object VideoExportBridge {
    @Throws(Exception::class)
    fun exportToMp4(videoPath: String, audioPath: String?, outputPath: String) {
        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            throw IOException("未找到缓存视频文件")
        }
        File(outputPath).parentFile?.mkdirs()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoPath)
            val videoTrackIndex = selectTrack(videoExtractor, "video/")
            if (videoTrackIndex == -1) {
                videoExtractor.release()
                throw IOException("缓存视频中没有可导出的画面轨道")
            }
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            videoExtractor.selectTrack(videoTrackIndex)
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            var audioExtractor: MediaExtractor? = null
            var muxerAudioTrack = -1
            if (!audioPath.isNullOrBlank()) {
                val audioFile = File(audioPath)
                if (audioFile.exists()) {
                    audioExtractor = MediaExtractor().apply {
                        setDataSource(audioPath)
                    }
                    val audioTrackIndex = selectTrack(audioExtractor, "audio/")
                    if (audioTrackIndex != -1) {
                        val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
                        audioExtractor.selectTrack(audioTrackIndex)
                        muxerAudioTrack = muxer.addTrack(audioFormat)
                    } else {
                        audioExtractor.release()
                        audioExtractor = null
                    }
                }
            }

            muxer.start()
            copySamples(videoExtractor, videoFormat, muxer, muxerVideoTrack)
            if (audioExtractor != null && muxerAudioTrack != -1) {
                val audioTrackIndex = audioExtractor.sampleTrackIndex.takeIf { it >= 0 }
                val audioFormat = if (audioTrackIndex != null) {
                    audioExtractor.getTrackFormat(audioTrackIndex)
                } else {
                    audioExtractor.getTrackFormat(selectTrack(audioExtractor, "audio/"))
                }
                copySamples(audioExtractor, audioFormat, muxer, muxerAudioTrack)
                audioExtractor.release()
            }
            videoExtractor.release()
        } finally {
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }

    private fun copySamples(
        extractor: MediaExtractor,
        trackFormat: MediaFormat,
        muxer: MediaMuxer,
        muxerTrack: Int,
    ) {
        val maxInputSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            262_144
        }
        val buffer = ByteBuffer.allocate(max(16 * 1024, maxInputSize))
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            buffer.position(0)
            buffer.limit(sampleSize)
            muxer.writeSampleData(muxerTrack, buffer, info)
            extractor.advance()
        }
    }
}
