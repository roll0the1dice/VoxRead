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
 * [formulaId] decides when the fullscreen formula opens and closes.
 * [activeNodeIds] are the symbols or subexpressions covered by the latest
 * spoken range. [utteranceId] distinguishes one spoken fragment from the next,
 * and [sequence] rejects a callback that arrives after a newer one.
 * [canonicalMathMl] is the formula from the same conversion as [activeNodeIds].
 */
data class TtsSpeechState(
    val session: Long,
    val sequence: Long,
    val play: TtsPlay,
    val chapterHref: String,
    val formulaId: String?,
    val activeNodeIds: List<String> = emptyList(),
    val utteranceId: String = "",
    val canonicalMathMl: String = "",
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
