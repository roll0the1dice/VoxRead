/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.android

import android.speech.tts.TextToSpeech
import kotlin.test.assertEquals
import org.junit.Test
import org.readium.navigator.media.tts.edge.EdgeTtsErrorCodes
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
class AndroidTtsErrorTest {
    @Test
    fun `service refusal is distinct from connection failure`() {
        assertEquals(
            AndroidTtsEngine.Error.Forbidden,
            AndroidTtsEngine.Error.fromNativeError(EdgeTtsErrorCodes.FORBIDDEN)
        )
        assertEquals(
            AndroidTtsEngine.Error.Network,
            AndroidTtsEngine.Error.fromNativeError(TextToSpeech.ERROR_NETWORK)
        )
        assertEquals(
            AndroidTtsEngine.Error.NetworkTimeout,
            AndroidTtsEngine.Error.fromNativeError(TextToSpeech.ERROR_NETWORK_TIMEOUT)
        )
        assertEquals(
            AndroidTtsEngine.Error.Synthesis,
            AndroidTtsEngine.Error.fromNativeError(TextToSpeech.ERROR_SYNTHESIS)
        )
    }
}
