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
}
