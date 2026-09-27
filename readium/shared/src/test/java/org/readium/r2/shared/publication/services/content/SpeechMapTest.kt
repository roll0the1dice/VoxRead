/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.publication.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
class SpeechMapTest {

    @Test
    fun `word range names the formula it lands on`() {
        val map = sentence("a", "vox:chap.xhtml#one", "b", "vox:chap.xhtml#two")
        assertEquals("vox:chap.xhtml#two", map.spokenMathId(2..2))
    }

    @Test
    fun `sentence level does not pick one formula out of several`() {
        val map = sentence("a", "vox:chap.xhtml#one", "b", "vox:chap.xhtml#two")
        assertNull(map.spokenMathId(null))
    }

    @Test
    fun `sentence level keeps a utterance that is one formula`() {
        val map = SpeechMap(
            listOf(math(0, 4, "vox:chap.xhtml#only"))
        )
        assertEquals("vox:chap.xhtml#only", map.spokenMathId(null))
    }

    private fun sentence(first: String, firstId: String, second: String, secondId: String): SpeechMap {
        val left = math(0, first.length, firstId)
        val right = math(first.length + 1, first.length + 1 + second.length, secondId)
        val gap = SpeechMap.Span(
            start = first.length,
            end = first.length + 1,
            kind = SpeechMap.Kind.Text,
            selector = "",
            raw = " ",
        )
        return SpeechMap(listOf(left, gap, right))
    }

    private fun math(start: Int, end: Int, id: String) = SpeechMap.Span(
        start = start,
        end = end,
        kind = SpeechMap.Kind.Math,
        selector = "",
        mathId = id,
    )
}
