/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import java.security.MessageDigest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Generates the `Sec-MS-GEC` handshake token used by Edge Read Aloud.
 *
 * Algorithm (same as `edge-tts` `drm.py`):
 * 1. Take the current Unix timestamp, plus any clock-skew correction.
 * 2. Shift it to the Windows FILETIME epoch (1601-01-01).
 * 3. Snap down to a 5-minute (300 s) window.
 * 4. Convert to 100-nanosecond ticks.
 * 5. SHA-256 the decimal ticks concatenated with the trusted client token.
 */
internal object EdgeTtsDrm {

    /** Seconds between 1601-01-01 and 1970-01-01. */
    internal const val WIN_EPOCH_SECONDS: Long = 11_644_473_600L

    private const val TICKS_PER_SECOND: Long = 10_000_000L

    private const val WINDOW_SECONDS: Long = 300L

    /** Clock skew vs Microsoft servers, in milliseconds. */
    private val clockSkewMillis: AtomicLong = AtomicLong(0L)

    fun clockSkewSeconds(): Double =
        clockSkewMillis.get() / 1000.0

    fun adjustClockSkewSeconds(deltaSeconds: Double) {
        clockSkewMillis.addAndGet((deltaSeconds * 1000.0).toLong())
    }

    fun adjustClockFromHttpDate(httpDate: String?): Boolean {
        val serverSeconds = parseRfc2616Date(httpDate) ?: return false
        val clientSeconds = unixSeconds()
        adjustClockSkewSeconds(serverSeconds - clientSeconds)
        return true
    }

    fun unixSeconds(nowMillis: Long = System.currentTimeMillis()): Double =
        (nowMillis + clockSkewMillis.get()) / 1000.0

    fun generateSecMsGec(nowMillis: Long = System.currentTimeMillis()): String {
        val unix = unixSeconds(nowMillis)
        var ticks = unix + WIN_EPOCH_SECONDS
        ticks -= ticks % WINDOW_SECONDS
        ticks *= TICKS_PER_SECOND.toDouble()
        val payload = formatTicks(ticks) + EdgeTtsConstants.TRUSTED_CLIENT_TOKEN
        return sha256Hex(payload).uppercase(Locale.US)
    }

    internal fun formatTicks(ticks: Double): String =
        String.format(Locale.US, "%.0f", ticks)

    internal fun parseRfc2616Date(date: String?): Double? {
        if (date.isNullOrBlank()) {
            return null
        }
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        )
        for (pattern in formats) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US)
                parser.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = parser.parse(date) ?: continue
                return parsed.time / 1000.0
            } catch (_: ParseException) {
                // Try the next pattern.
            }
        }
        return null
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.US_ASCII))
        return bytes.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }
}
