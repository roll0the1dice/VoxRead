/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

class EdgeTtsVoicesTest {

    @Test
    fun `official short name is unchanged`() {
        assertEquals(
            "zh-CN-YunxiNeural",
            EdgeTtsVoices.normalize("zh-CN-YunxiNeural")?.shortName
        )
    }

    @Test
    fun `missing neural suffix is restored`() {
        assertEquals(
            "zh-CN-YunxiNeural",
            EdgeTtsVoices.normalize("zh-CN-Yunxi")?.shortName
        )
    }

    @Test
    fun `lowercase identifier is restored`() {
        assertEquals(
            "zh-CN-YunxiNeural",
            EdgeTtsVoices.normalize("zh-cn-yunxineural")?.shortName
        )
    }

    @Test
    fun `underscores and spaces are stripped`() {
        assertEquals(
            "zh-CN-YunyangNeural",
            EdgeTtsVoices.normalize(" zh_CN_YunyangNeural ")?.shortName
        )
    }

    @Test
    fun `display name and person token resolve to official id`() {
        assertEquals("zh-CN-YunxiNeural", EdgeTtsVoices.normalize("云希")?.shortName)
        assertEquals("zh-CN-YunxiNeural", EdgeTtsVoices.normalize("Yunxi")?.shortName)
        assertEquals(
            "zh-CN-YunxiNeural",
            EdgeTtsVoices.normalize("Yunxi (zh-CN-YunxiNeural)")?.shortName
        )
    }

    @Test
    fun `xml lang follows the voice locale`() {
        val yunxi = assertNotNull(EdgeTtsVoices.normalize("zh-CN-YunxiNeural"))
        assertEquals("zh-CN", EdgeTtsVoices.xmlLang(yunxi))
        val ava = assertNotNull(EdgeTtsVoices.normalize("en-US-AvaNeural"))
        assertEquals("en-US", EdgeTtsVoices.xmlLang(ava))
    }
}
