package com.roll0the1dice.voxread.desktop.tts

import java.util.Locale

object EdgeTtsDrm {
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L
    private const val TICKS_PER_SECOND = 10_000_000L
    private const val WINDOW_SECONDS = 300L

    fun generateSecMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        val unix = nowMillis / 1000.0
        var ticks = unix + WIN_EPOCH_SECONDS
        ticks -= ticks % WINDOW_SECONDS
        ticks *= TICKS_PER_SECOND.toDouble()
        val formattedTicks = String.format(Locale.US, "%.0f", ticks)
        val payload = formattedTicks + EdgeTtsConstants.TRUSTED_CLIENT_TOKEN
        return EdgeTtsTokenV2.hash(payload)
    }
}
