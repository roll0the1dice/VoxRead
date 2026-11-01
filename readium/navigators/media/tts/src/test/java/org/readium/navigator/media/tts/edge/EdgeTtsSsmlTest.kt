/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class EdgeTtsSsmlTest {

    @Test
    fun `rate 1_0 becomes plus zero percent`() {
        assertEquals("+0%", EdgeTtsSsml.rateToSsml(1.0))
    }

    @Test
    fun `rate 1_5 becomes plus fifty percent`() {
        assertEquals("+50%", EdgeTtsSsml.rateToSsml(1.5))
    }

    @Test
    fun `rate 0_5 becomes minus fifty percent`() {
        assertEquals("-50%", EdgeTtsSsml.rateToSsml(0.5))
    }

    @Test
    fun `pitch 1_0 becomes plus zero hertz`() {
        assertEquals("+0Hz", EdgeTtsSsml.pitchToSsml(1.0))
    }

    @Test
    fun `xml special characters are escaped`() {
        val ssml = EdgeTtsSsml.wrap("Tom & Jerry <3", "en-US-AvaNeural", 1.0, 1.0)
        assertTrue(ssml.contains("Tom &amp; Jerry &lt;3"))
        assertTrue(ssml.contains("name='en-US-AvaNeural'"))
        assertTrue(ssml.contains("xml:lang='en-US'"))
        assertTrue(ssml.contains("rate='+0%'"))
    }

    @Test
    fun `chinese male voice uses official name and zh-CN lang`() {
        val ssml = EdgeTtsSsml.wrap("你好", " zh_cn_yunxineural ", 1.0, 1.0)
        assertTrue(ssml.contains("name='zh-CN-YunxiNeural'"))
        assertTrue(ssml.contains("xml:lang='zh-CN'"))
        assertTrue(!ssml.contains("name=' zh-CN-YunxiNeural '"))
    }

    @Test
    fun `control characters are replaced with spaces`() {
        val cleaned = EdgeTtsSsml.removeIncompatibleCharacters("a\u000b\u000cb")
        assertEquals("a  b", cleaned)
    }

    @Test
    fun `speech config requests 24 kHz mono mp3`() {
        val frame = EdgeTtsSsml.speechConfigFrame("timestamp")
        assertTrue(frame.contains("Path:speech.config"))
        assertTrue(frame.contains(EdgeTtsConstants.OUTPUT_FORMAT))
    }

    @Test
    fun `ssml frame uses Path ssml`() {
        val frame = EdgeTtsSsml.ssmlFrame("abc", "ts", "<speak/>")
        assertTrue(frame.startsWith("X-RequestId:abc"))
        assertTrue(frame.contains("Path:ssml"))
        assertTrue(frame.contains("<speak/>"))
    }

    @Test
    fun `volume defaults to plus 100 percent and scales with gain factor`() {
        val defaultSsml = EdgeTtsSsml.wrap("测试", "zh-CN-XiaoxiaoNeural", 1.0, 1.0)
        assertTrue(defaultSsml.contains("volume='+100%'"))
        assertEquals("+0%", EdgeTtsSsml.volumeToSsml(1.0))
        assertEquals("+50%", EdgeTtsSsml.volumeToSsml(1.25))
        assertEquals("+100%", EdgeTtsSsml.volumeToSsml(2.0))
        assertEquals("-50%", EdgeTtsSsml.volumeToSsml(0.5))
    }

    @Test
    fun `pcm processor boosts quiet signal without clipping`() {
        val processor = EdgeTtsPcmProcessor(gainFactor = 2.0, drcEnabled = true)
        val pcm = ByteArray(200)
        // Fill with a moderate 16-bit amplitude (4000 out of 32767)
        for (i in 0 until 100) {
            val sample = 4000
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        processor.processInPlace(pcm)
        val lastLo = pcm[198].toInt() and 0xFF
        val lastHi = pcm[199].toInt()
        val boosted = (lastHi shl 8) or lastLo
        assertTrue(boosted > 6000, "Expected boosted sample > 6000, got $boosted")
        assertTrue(boosted <= 32767)
    }
}
