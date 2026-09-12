/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader.tts

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import org.readium.r2.testapp.R

/**
 * Reading-friendly highlight tints for the currently spoken TTS utterance.
 *
 * The navigator decoration template applies its own alpha, so these values stay opaque.
 */
enum class TtsHighlightColor(
    val id: String,
    @ColorInt val tint: Int,
    @StringRes val label: Int,
) {
    YELLOW("yellow", Color.rgb(249, 239, 125), R.string.tts_highlight_color_yellow),
    GREEN("green", Color.rgb(173, 247, 123), R.string.tts_highlight_color_green),
    BLUE("blue", Color.rgb(124, 198, 247), R.string.tts_highlight_color_blue),
    RED("red", Color.rgb(247, 124, 124), R.string.tts_highlight_color_red),
    ;

    companion object {
        val DEFAULT: TtsHighlightColor = YELLOW

        fun fromId(id: String?): TtsHighlightColor =
            entries.find { it.id == id } ?: DEFAULT
    }
}
