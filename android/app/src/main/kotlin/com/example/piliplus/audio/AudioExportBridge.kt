package com.example.piliplus.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

object AudioExportBridge {
    private const val TIMEOUT_US = 10_000L
    private const val AAC_BITRATE = 192_000
    private const val MP3_BITRATE = 192

    @Throws(Exception::class)
    fun transcodeAudio(inputPath: String, outputPath: String, format: String) {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            throw IOException("未找到输入音频文件")
        }
        File(outputPath).parentFile?.mkdirs()
        when (format) {
            "mp3" -> transcodeToMp3(inputPath, outputPath)
            "wav" -> transcodeToWav(inputPath, outputPath)
            "m4a" -> transcodeToM4a(inputPath, outputPath)
            "aac" -> transcodeToAac(inputPath, outputPath)
            "flac" -> transcodeToFlac(inputPath, outputPath)
            else -> throw IllegalArgumentException("暂不支持的导出格式: $format")
        }
    }

    private fun transcodeToWav(inputPath: String, outputPath: String) {
        var writer: WavFileWriter? = null
        try {
            decodeToPcm(inputPath) { sampleRate, channelCount, pcmBytes, isEnd ->
                if (writer == null) {
                    writer = WavFileWriter(outputPath, sampleRate, channelCount)
                }
                if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                    writer!!.write(pcmBytes, pcmBytes.size)
                }
                if (isEnd) {
                    writer?.close()
                }
            }
        } catch (e: Exception) {
            writer?.closeQuietly()
            throw e
        }
    }

    private fun transcodeToMp3(inputPath: String, outputPath: String) {
        var encoder: Mp3Encoder? = null
        try {
            decodeToPcm(inputPath) { sampleRate, channelCount, pcmBytes, isEnd ->
                if (encoder == null) {
                    encoder = Mp3Encoder(outputPath, sampleRate, channelCount)
                }
                if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                    encoder!!.encode(pcmBytes, pcmBytes.size)
                }
                if (isEnd) {
                    encoder?.finish()
                }
            }
        } catch (e: Exception) {
            encoder?.closeQuietly()
            throw e
        }
    }

    private fun transcodeToM4a(inputPath: String, outputPath: String) {
        val trackInfo = prepareAudioTrack(inputPath)
        trackInfo.use { info ->
            val mime = info.trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == "audio/mp4a-latm") {
                remuxAacTrack(info.extractor, info.trackIndex, info.trackFormat, outputPath)
            } else {
                encodeToAacM4a(inputPath, outputPath)
            }
        }
    }

    private fun transcodeToAac(inputPath: String, outputPath: String) {
        val trackInfo = prepareAudioTrack(inputPath)
        trackInfo.use { info ->
            val mime = info.trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == "audio/mp4a-latm") {
                remuxAacTrackToAdts(info.extractor, info.trackIndex, info.trackFormat, outputPath)
            } else {
                encodeToAacAdts(inputPath, outputPath)
            }
        }
    }

    private fun transcodeToFlac(inputPath: String, outputPath: String) {
        encodeToFlac(inputPath, outputPath)
    }

    private fun remuxAacTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        trackFormat: MediaFormat,
        outputPath: String,
    ) {
        extractor.selectTrack(trackIndex)
        val maxInputSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            262_144
        }
        val buffer = ByteBuffer.allocate(max(16 * 1024, maxInputSize))
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val dstTrack = muxer.addTrack(trackFormat)
            muxer.start()
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
                muxer.writeSampleData(dstTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            try {
                muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
        }
    }

    private fun remuxAacTrackToAdts(
        extractor: MediaExtractor,
        trackIndex: Int,
        trackFormat: MediaFormat,
        outputPath: String,
    ) {
        extractor.selectTrack(trackIndex)
        val maxInputSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            262_144
        }
        val buffer = ByteBuffer.allocate(max(16 * 1024, maxInputSize))
        val config = extractAacConfig(trackFormat)
        FileOutputStream(outputPath).use { outputStream ->
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break
                }
                writeAdtsFrame(outputStream, buffer, sampleSize, config)
                extractor.advance()
            }
        }
    }

    private fun encodeToAacM4a(inputPath: String, outputPath: String) {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputTrackIndex = -1
        var encodeSampleRate = 44_100
        var encodeChannelCount = 2
        var encoderInputEnded = false
        var nextPresentationTimeUs = 0L
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            decodeToPcm(inputPath) { sampleRate, channelCount, pcmBytes, isEnd ->
                if (encoder == null) {
                    encodeSampleRate = sampleRate
                    encodeChannelCount = channelCount
                    require(encodeChannelCount in 1..2) { "M4A 导出仅支持单声道或双声道" }
                    val format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        encodeSampleRate,
                        encodeChannelCount,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                    }
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder!!.start()
                    muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }

                if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                    nextPresentationTimeUs = queueEncoderInput(
                        encoder = encoder!!,
                        data = pcmBytes,
                        size = pcmBytes.size,
                        endOfStream = false,
                        startPresentationTimeUs = nextPresentationTimeUs,
                        channelCount = encodeChannelCount,
                        sampleRate = encodeSampleRate,
                    )
                }
                if (isEnd && !encoderInputEnded) {
                    queueEncoderInput(
                        encoder = encoder!!,
                        data = ByteArray(0),
                        size = 0,
                        endOfStream = true,
                        startPresentationTimeUs = nextPresentationTimeUs,
                        channelCount = encodeChannelCount,
                        sampleRate = encodeSampleRate,
                    )
                    encoderInputEnded = true
                }

                var idleTries = 0
                while (true) {
                    val outputIndex = encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (isEnd && idleTries < 100) {
                                idleTries++
                                continue
                            }
                            break
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            idleTries = 0
                            if (muxerStarted) {
                                throw IOException("AAC 编码器输出格式重复变化")
                            }
                            outputTrackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            idleTries = 0
                            val outputBuffer = encoder!!.getOutputBuffer(outputIndex)
                                ?: throw IOException("读取 AAC 输出缓冲区失败")
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                if (!muxerStarted) {
                                    throw IOException("AAC Muxer 尚未启动")
                                }
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer!!.writeSampleData(outputTrackIndex, outputBuffer, bufferInfo)
                            }
                            encoder!!.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            encoder?.release()
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (_: Exception) {
            }
            muxer?.release()
        }
    }

    private fun encodeToAacAdts(inputPath: String, outputPath: String) {
        var encoder: MediaCodec? = null
        var outputStream: FileOutputStream? = null
        var encodeSampleRate = 44_100
        var encodeChannelCount = 2
        var encoderInputEnded = false
        var nextPresentationTimeUs = 0L
        var aacConfig = AacConfig(
            audioObjectType = 2,
            sampleRate = encodeSampleRate,
            sampleRateIndex = sampleRateToAdtsIndex(encodeSampleRate),
            channelCount = encodeChannelCount,
        )
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            decodeToPcm(inputPath) { sampleRate, channelCount, pcmBytes, isEnd ->
                if (encoder == null) {
                    encodeSampleRate = sampleRate
                    encodeChannelCount = channelCount
                    require(encodeChannelCount in 1..2) { "AAC 导出仅支持单声道或双声道" }
                    val format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        encodeSampleRate,
                        encodeChannelCount,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                    }
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder!!.start()
                    outputStream = FileOutputStream(outputPath)
                    aacConfig = AacConfig(
                        audioObjectType = 2,
                        sampleRate = encodeSampleRate,
                        sampleRateIndex = sampleRateToAdtsIndex(encodeSampleRate),
                        channelCount = encodeChannelCount,
                    )
                }

                if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                    nextPresentationTimeUs = queueEncoderInput(
                        encoder = encoder!!,
                        data = pcmBytes,
                        size = pcmBytes.size,
                        endOfStream = false,
                        startPresentationTimeUs = nextPresentationTimeUs,
                        channelCount = encodeChannelCount,
                        sampleRate = encodeSampleRate,
                    )
                }
                if (isEnd && !encoderInputEnded) {
                    queueEncoderInput(
                        encoder = encoder!!,
                        data = ByteArray(0),
                        size = 0,
                        endOfStream = true,
                        startPresentationTimeUs = nextPresentationTimeUs,
                        channelCount = encodeChannelCount,
                        sampleRate = encodeSampleRate,
                    )
                    encoderInputEnded = true
                }

                var idleTries = 0
                while (true) {
                    val outputIndex = encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (isEnd && idleTries < 100) {
                                idleTries++
                                continue
                            }
                            break
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            idleTries = 0
                            aacConfig = extractAacConfig(encoder!!.outputFormat)
                        }
                        outputIndex >= 0 -> {
                            idleTries = 0
                            val outputBuffer = encoder!!.getOutputBuffer(outputIndex)
                                ?: throw IOException("读取 AAC 输出缓冲区失败")
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                writeAdtsFrame(outputStream!!, outputBuffer, bufferInfo.size, aacConfig)
                            }
                            encoder!!.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            encoder?.release()
            try {
                outputStream?.flush()
            } catch (_: Exception) {
            }
            outputStream?.close()
        }
    }

    private fun encodeToFlac(inputPath: String, outputPath: String) {
        var encoder: MediaCodec? = null
        var outputStream: FileOutputStream? = null
        var encodeSampleRate = 44_100
        var encodeChannelCount = 2
        var encoderInputEnded = false
        var nextPresentationTimeUs = 0L
        var headerWritten = false
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            decodeToPcm(inputPath) { sampleRate, channelCount, pcmBytes, isEnd ->
                if (encoder == null) {
                    encodeSampleRate = sampleRate
                    encodeChannelCount = channelCount
                    require(encodeChannelCount in 1..2) { "FLAC 导出仅支持单声道或双声道" }
                    val format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_FLAC,
                        encodeSampleRate,
                        encodeChannelCount,
                    ).apply {
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                    }
                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
                    encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder!!.start()
                    outputStream = FileOutputStream(outputPath)
                }

                if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                    nextPresentationTimeUs = queueEncoderInput(
                        encoder = encoder!!,
                        data = pcmBytes,
                        size = pcmBytes.size,
                        endOfStream = false,
                        startPresentationTimeUs = nextPresentationTimeUs,
                        channelCount = encodeChannelCount,
                        sampleRate = encodeSampleRate,
                    )
                }
                if (isEnd && !encoderInputEnded) {
                    queueEncoderInput(
                        encoder = encoder!!,
                        data = ByteArray(0),
                        size = 0,
                        endOfStream = true,
                        startPresentationTimeUs = nextPresentationTimeUs,
                        channelCount = encodeChannelCount,
                        sampleRate = encodeSampleRate,
                    )
                    encoderInputEnded = true
                }

                var idleTries = 0
                while (true) {
                    val outputIndex = encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (isEnd && idleTries < 100) {
                                idleTries++
                                continue
                            }
                            break
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            idleTries = 0
                            val csd0 = encoder!!.outputFormat.getByteBufferCopy("csd-0")
                            if (!headerWritten && csd0 != null && csd0.isNotEmpty()) {
                                outputStream!!.write(csd0)
                                headerWritten = true
                            }
                        }
                        outputIndex >= 0 -> {
                            idleTries = 0
                            val outputBuffer = encoder!!.getOutputBuffer(outputIndex)
                                ?: throw IOException("读取 FLAC 输出缓冲区失败")
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            if (bufferInfo.size > 0) {
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.get(data)
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    if (!headerWritten) {
                                        outputStream!!.write(data)
                                        headerWritten = true
                                    }
                                } else {
                                    outputStream!!.write(data)
                                }
                            }
                            encoder!!.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            encoder?.release()
            try {
                outputStream?.flush()
            } catch (_: Exception) {
            }
            outputStream?.close()
        }
    }

    private fun queueEncoderInput(
        encoder: MediaCodec,
        data: ByteArray,
        size: Int,
        endOfStream: Boolean,
        startPresentationTimeUs: Long,
        channelCount: Int,
        sampleRate: Int,
    ): Long {
        var offset = 0
        var presentationTimeUs = startPresentationTimeUs
        val bytesPerFrame = channelCount * 2
        while (offset < size || (size == 0 && endOfStream && offset == 0)) {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                continue
            }
            val inputBuffer = encoder.getInputBuffer(inputIndex)
                ?: throw IOException("读取编码输入缓冲区失败")
            inputBuffer.clear()
            val chunkSize = minOf(size - offset, inputBuffer.remaining())
            if (chunkSize > 0) {
                inputBuffer.put(data, offset, chunkSize)
            }
            val flags = if (endOfStream && offset + chunkSize >= size) {
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            } else {
                0
            }
            encoder.queueInputBuffer(
                inputIndex,
                0,
                chunkSize.coerceAtLeast(0),
                presentationTimeUs,
                flags,
            )
            offset += chunkSize
            if (chunkSize > 0) {
                val frameCount = chunkSize / bytesPerFrame
                presentationTimeUs += frameCount * 1_000_000L / sampleRate
            }
            if (size == 0) {
                break
            }
        }
        return presentationTimeUs
    }

    private fun decodeToPcm(
        inputPath: String,
        onChunk: (sampleRate: Int, channelCount: Int, pcmBytes: ByteArray?, isEnd: Boolean) -> Unit,
    ) {
        val trackInfo = prepareAudioTrack(inputPath)
        trackInfo.use { info ->
            val extractor = info.extractor
            extractor.selectTrack(info.trackIndex)
            val mime = info.trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("无法识别音频 MIME")
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(info.trackFormat, null, null, 0)
                decoder.start()

                var inputDone = false
                var outputDone = false
                val bufferInfo = MediaCodec.BufferInfo()
                var sampleRate = if (info.trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    info.trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    44_100
                }
                var channelCount = if (info.trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    info.trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                }

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: throw IOException("读取解码输入缓冲区失败")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    extractor.sampleFlags,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            val pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                                throw IOException("当前仅支持 PCM 16-bit 解码输出")
                            }
                        }
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: throw IOException("读取解码输出缓冲区失败")
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)
                                onChunk(sampleRate, channelCount, chunk, false)
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                onChunk(sampleRate, channelCount, null, true)
            } finally {
                try {
                    decoder.stop()
                } catch (_: Exception) {
                }
                decoder.release()
            }
        }
    }

    private fun prepareAudioTrack(inputPath: String): AudioTrackInfo {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IOException("未找到可导出的音频轨")
        }
        val format = extractor.getTrackFormat(trackIndex)
        return AudioTrackInfo(extractor, trackIndex, format)
    }

    private data class AudioTrackInfo(
        val extractor: MediaExtractor,
        val trackIndex: Int,
        val trackFormat: MediaFormat,
    ) : AutoCloseable {
        override fun close() {
            extractor.release()
        }
    }

    private data class AacConfig(
        val audioObjectType: Int,
        val sampleRate: Int,
        val sampleRateIndex: Int,
        val channelCount: Int,
    )

    private class WavFileWriter(
        outputPath: String,
        private val sampleRate: Int,
        private val channelCount: Int,
    ) : AutoCloseable {
        private val file = RandomAccessFile(outputPath, "rw")
        private var dataSize: Long = 0

        init {
            file.setLength(0)
            file.write(ByteArray(44))
        }

        fun write(data: ByteArray, size: Int) {
            file.write(data, 0, size)
            dataSize += size
        }

        override fun close() {
            writeHeader()
            file.close()
        }

        fun closeQuietly() {
            try {
                close()
            } catch (_: Exception) {
            }
        }

        private fun writeHeader() {
            val byteRate = sampleRate * channelCount * 2
            val blockAlign = channelCount * 2
            file.seek(0)
            file.writeBytes("RIFF")
            file.writeIntLE((36 + dataSize).toInt())
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            file.writeIntLE(16)
            file.writeShortLE(1)
            file.writeShortLE(channelCount)
            file.writeIntLE(sampleRate)
            file.writeIntLE(byteRate)
            file.writeShortLE(blockAlign)
            file.writeShortLE(16)
            file.writeBytes("data")
            file.writeIntLE(dataSize.toInt())
        }
    }

    private class Mp3Encoder(
        outputPath: String,
        sampleRate: Int,
        private val channelCount: Int,
    ) : AutoCloseable {
        private val outputStream = FileOutputStream(outputPath)
        private val lame: AndroidLame
        private var closed = false

        init {
            require(channelCount in 1..2) { "MP3 导出仅支持单声道或双声道" }
            lame = LameBuilder()
                .setInSampleRate(sampleRate)
                .setOutChannels(channelCount)
                .setOutBitrate(MP3_BITRATE)
                .setOutSampleRate(sampleRate)
                .build()
        }

        fun encode(data: ByteArray, size: Int) {
            if (size <= 0) return
            if (size % 2 != 0) {
                throw IOException("PCM 数据长度异常")
            }
            val pcmShorts = ShortArray(size / 2)
            ByteBuffer.wrap(data, 0, size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(pcmShorts)
            val samplesPerChannel = pcmShorts.size / channelCount
            if (samplesPerChannel <= 0) {
                return
            }
            if (channelCount == 2 && pcmShorts.size % 2 != 0) {
                throw IOException("双声道 PCM 数据长度异常")
            }
            val mp3Buffer = ByteArray((7200 + pcmShorts.size * 1.25).toInt())
            val written = if (channelCount == 1) {
                lame.encode(pcmShorts, pcmShorts, samplesPerChannel, mp3Buffer)
            } else {
                val left = ShortArray(samplesPerChannel)
                val right = ShortArray(samplesPerChannel)
                var srcIndex = 0
                for (i in 0 until samplesPerChannel) {
                    left[i] = pcmShorts[srcIndex++]
                    right[i] = pcmShorts[srcIndex++]
                }
                lame.encode(left, right, samplesPerChannel, mp3Buffer)
            }
            if (written < 0) {
                throw IOException("MP3 编码失败，错误码: $written")
            }
            if (written > 0) {
                outputStream.write(mp3Buffer, 0, written)
            }
        }

        fun finish() {
            if (closed) return
            val mp3Buffer = ByteArray(7200)
            val flushed = lame.flush(mp3Buffer)
            if (flushed < 0) {
                throw IOException("MP3 收尾失败，错误码: $flushed")
            }
            if (flushed > 0) {
                outputStream.write(mp3Buffer, 0, flushed)
            }
            close()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                outputStream.flush()
            } finally {
                outputStream.close()
                lame.close()
            }
        }

        fun closeQuietly() {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractAacConfig(format: MediaFormat): AacConfig {
        val fallbackSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            44_100
        }
        val fallbackChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            2
        }
        val csd0 = format.getByteBufferCopy("csd-0")
        if (csd0 != null && csd0.size >= 2) {
            val b0 = csd0[0].toInt() and 0xFF
            val b1 = csd0[1].toInt() and 0xFF
            var audioObjectType = (b0 shr 3) and 0x1F
            val sampleRateIndex = ((b0 and 0x07) shl 1) or (b1 shr 7)
            val channelConfig = (b1 shr 3) and 0x0F
            if (audioObjectType == 0) {
                audioObjectType = 2
            }
            val sampleRate = adtsIndexToSampleRate(sampleRateIndex) ?: fallbackSampleRate
            return AacConfig(
                audioObjectType = audioObjectType,
                sampleRate = sampleRate,
                sampleRateIndex = sampleRateToAdtsIndex(sampleRate),
                channelCount = channelConfig.takeIf { it > 0 } ?: fallbackChannelCount,
            )
        }
        return AacConfig(
            audioObjectType = 2,
            sampleRate = fallbackSampleRate,
            sampleRateIndex = sampleRateToAdtsIndex(fallbackSampleRate),
            channelCount = fallbackChannelCount,
        )
    }

    private fun writeAdtsFrame(
        outputStream: FileOutputStream,
        buffer: ByteBuffer,
        sampleSize: Int,
        config: AacConfig,
    ) {
        if (sampleSize <= 0) return
        val header = buildAdtsHeader(sampleSize, config)
        outputStream.write(header)
        val data = ByteArray(sampleSize)
        buffer.get(data)
        outputStream.write(data)
    }

    private fun buildAdtsHeader(sampleSize: Int, config: AacConfig): ByteArray {
        val header = ByteArray(7)
        val frameLength = sampleSize + header.size
        val profile = (config.audioObjectType - 1).coerceAtLeast(0)
        val sampleRateIndex = config.sampleRateIndex.coerceIn(0, 12)
        val channelConfig = config.channelCount.coerceIn(1, 7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        header[2] = (((profile and 0x03) shl 6) or ((sampleRateIndex and 0x0F) shl 2) or ((channelConfig shr 2) and 0x01)).toByte()
        header[3] = ((((channelConfig and 0x03) shl 6) or ((frameLength shr 11) and 0x03))).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = ((((frameLength and 0x07) shl 5) or 0x1F)).toByte()
        header[6] = 0xFC.toByte()
        return header
    }

    private fun sampleRateToAdtsIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96_000 -> 0
            88_200 -> 1
            64_000 -> 2
            48_000 -> 3
            44_100 -> 4
            32_000 -> 5
            24_000 -> 6
            22_050 -> 7
            16_000 -> 8
            12_000 -> 9
            11_025 -> 10
            8_000 -> 11
            7_350 -> 12
            else -> 4
        }
    }

    private fun adtsIndexToSampleRate(index: Int): Int? {
        return when (index) {
            0 -> 96_000
            1 -> 88_200
            2 -> 64_000
            3 -> 48_000
            4 -> 44_100
            5 -> 32_000
            6 -> 24_000
            7 -> 22_050
            8 -> 16_000
            9 -> 12_000
            10 -> 11_025
            11 -> 8_000
            12 -> 7_350
            else -> null
        }
    }
}

private fun MediaFormat.getByteBufferCopy(key: String): ByteArray? {
    val source = getByteBuffer(key) ?: return null
    val duplicate = source.duplicate()
    val data = ByteArray(duplicate.remaining())
    duplicate.get(data)
    return data
}

private fun RandomAccessFile.writeIntLE(value: Int) {
    write(byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    ))
}

private fun RandomAccessFile.writeShortLE(value: Int) {
    write(byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    ))
}
