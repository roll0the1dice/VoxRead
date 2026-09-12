/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import android.media.MediaCodec
import android.media.MediaFormat
import timber.log.Timber

/**
 * Streaming MP3 → 16-bit PCM decoder backed by [MediaCodec].
 *
 * Compressed chunks from the WebSocket are queued as soon as they arrive;
 * decoded PCM is delivered through [onPcm] without waiting for the full utterance.
 */
internal class Mp3StreamDecoder(
    private val sampleRateHz: Int = EdgeTtsConstants.SAMPLE_RATE_HZ,
    private val channelCount: Int = EdgeTtsConstants.CHANNEL_COUNT,
) : AutoCloseable {

    private var codec: MediaCodec? = null

    fun start() {
        close()
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_MPEG,
            sampleRateHz,
            channelCount
        )
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
        decoder.configure(format, null, null, 0)
        decoder.start()
        codec = decoder
    }

    fun decode(mp3: ByteArray, onPcm: (ByteArray) -> Unit) {
        val decoder = codec ?: return
        if (mp3.isEmpty()) {
            return
        }
        var offset = 0
        while (offset < mp3.size) {
            val index = dequeueInput(decoder) ?: continue
            val input = decoder.getInputBuffer(index) ?: continue
            val toCopy = minOf(input.remaining(), mp3.size - offset)
            input.clear()
            input.put(mp3, offset, toCopy)
            decoder.queueInputBuffer(index, 0, toCopy, 0L, 0)
            offset += toCopy
            drain(decoder, onPcm, waitForEos = false)
        }
    }

    fun finish(onPcm: (ByteArray) -> Unit) {
        val decoder = codec ?: return
        val index = dequeueInput(decoder, timeoutUs = 50_000L) ?: return
        decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(decoder, onPcm, waitForEos = true)
    }

    fun reset() {
        close()
    }

    override fun close() {
        val decoder = codec ?: return
        codec = null
        try {
            decoder.stop()
        } catch (e: IllegalStateException) {
            Timber.w(e, "MediaCodec.stop() failed")
        }
        try {
            decoder.release()
        } catch (e: IllegalStateException) {
            Timber.w(e, "MediaCodec.release() failed")
        }
    }

    private fun dequeueInput(decoder: MediaCodec, timeoutUs: Long = 10_000L): Int? {
        val index = decoder.dequeueInputBuffer(timeoutUs)
        return index.takeIf { it >= 0 }
    }

    private fun drain(
        decoder: MediaCodec,
        onPcm: (ByteArray) -> Unit,
        waitForEos: Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        var idleTries = 0
        while (true) {
            val timeoutUs = if (waitForEos) 10_000L else 0L
            when (val index = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEos) {
                        return
                    }
                    idleTries += 1
                    if (idleTries > 50) {
                        return
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Keep draining; the new format is PCM 16-bit.
                }
                else -> {
                    if (index < 0) {
                        continue
                    }
                    try {
                        val skip = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!skip && info.size > 0) {
                            val output = decoder.getOutputBuffer(index)
                            if (output != null) {
                                val pcm = ByteArray(info.size)
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                output.get(pcm)
                                onPcm(pcm)
                            }
                        }
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }
}
