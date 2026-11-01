package com.roll0the1dice.voxread.desktop.tts

import androidx.compose.ui.graphics.Color

/**
 * Reading-friendly highlight tints for the currently spoken TTS utterance.
 * 1:1 对齐 Android 原版 org.readium.r2.testapp.reader.tts.TtsHighlightColor.kt
 */
enum class TtsHighlightColor(
    val id: String,
    val tintHex: String,
    val label: String,
    val rgb: Triple<Int, Int, Int>
) {
    YELLOW("yellow", "#F9EF7D", "柔和黄", Triple(255, 220, 40)),
    GREEN("green", "#ADF77B", "护眼绿", Triple(76, 175, 80)),
    BLUE("blue", "#7CC6F7", "天空蓝", Triple(33, 150, 243)),
    RED("red", "#F77C7C", "珊瑚红", Triple(244, 67, 54));

    val composeColor: Color get() = Color(rgb.first, rgb.second, rgb.third)

    fun playingFill(): String = rgba(if (this == YELLOW) 0.45f else 0.40f)
    fun pausedFill(): String = rgba(if (this == YELLOW) 0.22f else 0.18f)
    fun speakEdge(): String = rgba(0.95f)

    private fun rgba(alpha: Float): String = "rgba(${rgb.first}, ${rgb.second}, ${rgb.third}, $alpha)"

    companion object {
        val Default: TtsHighlightColor = YELLOW

        fun fromId(id: String): TtsHighlightColor =
            entries.find { it.id == id } ?: Default
    }
}
