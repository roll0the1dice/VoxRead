/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import kotlin.math.roundToInt
import org.readium.navigator.media.tts.edge.EdgeTtsConstants.MAX_SSML_BYTES

/**
 * Builds Edge Read Aloud SSML and the two text frames sent after the WebSocket handshake.
 */
internal object EdgeTtsSsml {

    fun speechConfigFrame(timestamp: String): String =
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{""" +
            """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"true"},""" +
            """"outputFormat":"${EdgeTtsConstants.OUTPUT_FORMAT}"}}}}""" +
            "\r\n"

    fun ssmlFrame(requestId: String, timestamp: String, ssml: String): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml

    fun wrap(text: String, voice: String, rate: Double, pitch: Double): String {
        val escaped = escapeXml(removeIncompatibleCharacters(text))
        return wrapEscaped(escaped, voice, rate, pitch)
    }

    fun chunkText(text: String, maxBytes: Int = MAX_SSML_BYTES): List<String> {
        val sanitized = escapeXml(removeIncompatibleCharacters(text))
        val utf8 = sanitized.toByteArray(Charsets.UTF_8)
        if (utf8.size <= maxBytes) {
            return listOf(sanitized)
        }
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < sanitized.length) {
            var end = sanitized.length
            while (end > offset && sanitized.substring(offset, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
                val window = sanitized.substring(offset, end)
                val split = window.lastIndexOfAny(charArrayOf('\n', ' ', '，', '。', '、', ',', '.'))
                end = if (split > 0) offset + split else end - 1
            }
            val chunk = sanitized.substring(offset, end).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            offset = end
        }
        return chunks.ifEmpty { listOf("") }
    }

    fun wrapEscaped(escapedText: String, voice: String, rate: Double, pitch: Double): String {
        val entry = EdgeTtsVoices.normalize(voice)
        val official = entry?.shortName
            ?: EdgeTtsVoices.sanitizeIdentifier(voice)
            ?: voice.trim()
        val lang = entry?.let { EdgeTtsVoices.xmlLang(it) }
            ?: EdgeTtsVoices.xmlLangFromName(official)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$official'>" +
            "<prosody pitch='${pitchToSsml(pitch)}' rate='${rateToSsml(rate)}' volume='+0%'>" +
            escapedText +
            "</prosody></voice></speak>"
    }

    fun rateToSsml(rate: Double): String =
        formatSignedPercent(rate)

    fun pitchToSsml(pitch: Double): String {
        val percent = ((pitch - 1.0) * 100.0).roundToInt()
        return if (percent >= 0) "+${percent}Hz" else "${percent}Hz"
    }

    fun removeIncompatibleCharacters(text: String): String =
        buildString(text.length) {
            for (char in text) {
                val code = char.code
                append(
                    if ((code in 0..8) || (code in 11..12) || (code in 14..31)) {
                        ' '
                    } else {
                        char
                    }
                )
            }
        }

    fun escapeXml(text: String): String =
        buildString(text.length) {
            for (char in text) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(char)
                }
            }
        }

    private fun formatSignedPercent(factor: Double): String {
        val percent = ((factor - 1.0) * 100.0).roundToInt()
        return if (percent >= 0) "+$percent%" else "$percent%"
    }
}
