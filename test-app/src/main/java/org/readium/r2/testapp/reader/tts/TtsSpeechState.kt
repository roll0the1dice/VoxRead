/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalReadiumApi::class)

package org.readium.r2.testapp.reader.tts

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.content.SpeechAnchors

enum class TtsPlay {
    Playing,
    Paused,
    Stopped,
}

/**
 * The one TTS snapshot both the page and the fullscreen viewer read.
 * [formulaId] is book, chapter, and the original formula id. It is absent when
 * the engine is only at sentence level and the sentence is not one formula.
 */
data class TtsSpeechState(
    val session: Long,
    val sequence: Long,
    val play: TtsPlay,
    val chapterHref: String,
    val formulaId: String?,
) {
    companion object {
        fun key(bookId: Long, href: String, formulaId: String): String {
            val local = SpeechAnchors.localId(formulaId).ifBlank { formulaId }
            return "$bookId\u0000$href\u0000$local"
        }

        fun stopped(sequence: Long) = TtsSpeechState(
            session = 0,
            sequence = sequence,
            play = TtsPlay.Stopped,
            chapterHref = "",
            formulaId = null,
        )
    }
}
