/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

/**
 * Strips the Microsoft custom header from a binary WebSocket frame.
 *
 * Layout:
 * - bytes 0-1: header length as a big-endian unsigned short
 * - following [headerLength] bytes: text metadata (`Path:audio`, request id, …)
 * - remainder: raw MP3
 */
internal object EdgeTtsAudioParser {

    data class TextMessage(
        val path: String?,
        val body: String,
        val headers: Map<String, String>,
    )

    fun extractMp3(frame: ByteArray): ByteArray? {
        if (frame.size < 2) {
            return null
        }
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val audioOffset = 2 + headerLength
        if (headerLength < 0 || audioOffset > frame.size) {
            return null
        }
        val headers = if (headerLength == 0) {
            ""
        } else {
            String(frame, 2, headerLength, Charsets.UTF_8)
        }
        if (!headers.contains("Path:audio", ignoreCase = true)) {
            return null
        }
        if (audioOffset == frame.size) {
            return ByteArray(0)
        }
        return frame.copyOfRange(audioOffset, frame.size)
    }

    fun parseTextMessage(text: String): TextMessage {
        val separator = text.indexOf("\r\n\r\n")
        val headerBlock = if (separator >= 0) text.substring(0, separator) else text
        val body = if (separator >= 0) text.substring(separator + 4) else ""
        val headers = linkedMapOf<String, String>()
        for (line in headerBlock.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon <= 0) {
                continue
            }
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return TextMessage(
            path = headers["Path"],
            body = body,
            headers = headers
        )
    }
}
