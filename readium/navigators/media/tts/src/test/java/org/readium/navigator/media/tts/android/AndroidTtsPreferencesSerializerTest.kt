/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.android

import kotlin.test.assertEquals
import org.junit.Test
import org.readium.r2.shared.util.Language

class AndroidTtsPreferencesSerializerTest {

    @Test
    fun serializeAndDeserializeVoices() {
        val serializer = AndroidTtsPreferencesSerializer()
        val prefs = AndroidTtsPreferences(
            engine = AndroidTtsEngine.Kind.Edge,
            voices = mapOf(
                Language("zh") to AndroidTtsEngine.Voice.Id("zh-CN-YunxiNeural"),
                Language("zh-CN") to AndroidTtsEngine.Voice.Id("zh-CN-YunxiNeural")
            )
        )
        val json = serializer.serialize(prefs)
        val deserialized = serializer.deserialize(json)
        assertEquals(prefs, deserialized)
    }
}
