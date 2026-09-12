/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class EdgeTtsAudioParserTest {

    @Test
    fun `binary frame yields MP3 after the custom header`() {
        val header = "Path:audio\r\nContent-Type:audio/mpeg"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x10, 0x20)
        val frame = ByteArray(2 + headerBytes.size + mp3.size)
        frame[0] = ((headerBytes.size shr 8) and 0xFF).toByte()
        frame[1] = (headerBytes.size and 0xFF).toByte()
        headerBytes.copyInto(frame, 2)
        mp3.copyInto(frame, 2 + headerBytes.size)

        assertContentEquals(mp3, EdgeTtsAudioParser.extractMp3(frame))
    }

    @Test
    fun `empty audio terminator frame yields empty payload`() {
        val header = "Path:audio"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(2 + headerBytes.size)
        frame[0] = 0
        frame[1] = headerBytes.size.toByte()
        headerBytes.copyInto(frame, 2)

        val extracted = EdgeTtsAudioParser.extractMp3(frame)
        assertNotNullEmpty(extracted)
    }

    @Test
    fun `non-audio path is ignored`() {
        val header = "Path:response"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(2 + headerBytes.size + 1)
        frame[1] = headerBytes.size.toByte()
        headerBytes.copyInto(frame, 2)
        frame[frame.lastIndex] = 1

        assertNull(EdgeTtsAudioParser.extractMp3(frame))
    }

    @Test
    fun `text message exposes Path and body`() {
        val message = "X-RequestId:abc\r\nPath:turn.end\r\n\r\n"
        val parsed = EdgeTtsAudioParser.parseTextMessage(message)
        assertEquals("turn.end", parsed.path)
        assertEquals("", parsed.body)
    }

    @Test
    fun `audio metadata body is preserved`() {
        val body = """{"Metadata":[]}"""
        val message = "Path:audio.metadata\r\n\r\n$body"
        val parsed = EdgeTtsAudioParser.parseTextMessage(message)
        assertEquals("audio.metadata", parsed.path)
        assertEquals(body, parsed.body)
    }

    private fun assertNotNullEmpty(value: ByteArray?) {
        assertTrue(value != null && value.isEmpty())
    }
}
