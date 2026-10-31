package com.roll0the1dice.voxread.desktop.tts

import java.util.Locale

object EdgeTtsDrm:

  val WinEpochSeconds: Long = 11_644_473_600L
  val TicksPerSecond: Long = 10_000_000L
  val WindowSeconds: Long = 300L

  def generateSecMsGec(nowMillis: Long = System.currentTimeMillis()): String =
    val unix = nowMillis / 1000.0
    var ticks = unix + WinEpochSeconds
    ticks -= ticks % WindowSeconds
    ticks *= TicksPerSecond.toDouble
    val formattedTicks = String.format(Locale.US, "%.0f", ticks)
    val payload = formattedTicks + EdgeTtsConstants.TrustedClientToken
    EdgeTtsTokenV2.hash(payload)
