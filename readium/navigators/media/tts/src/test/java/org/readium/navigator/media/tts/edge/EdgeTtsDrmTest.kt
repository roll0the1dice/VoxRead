/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import java.security.MessageDigest
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

class EdgeTtsDrmTest {

    @Test
    fun `Sec-MS-GEC is SHA-256 of windowed FILETIME plus trusted token`() {
        val unixMillis = 1_700_000_000_000L
        val unixSeconds = unixMillis / 1000.0
        var ticks = unixSeconds + EdgeTtsDrm.WIN_EPOCH_SECONDS
        ticks -= ticks % 300.0
        ticks *= 10_000_000.0
        val payload = String.format(Locale.US, "%.0f", ticks) +
            EdgeTtsConstants.TRUSTED_CLIENT_TOKEN
        val expected = sha256Hex(payload).uppercase(Locale.US)

        assertEquals(expected, EdgeTtsDrm.generateSecMsGec(unixMillis))
    }

    @Test
    fun `tokens in the same 5-minute window match`() {
        val start = 1_699_999_800_000L
        val fourMinutesLater = start + 4 * 60 * 1000L
        assertEquals(
            EdgeTtsDrm.generateSecMsGec(start),
            EdgeTtsDrm.generateSecMsGec(fourMinutesLater)
        )
    }

    @Test
    fun `tokens across a 5-minute boundary differ`() {
        val before = 1_700_000_000_000L
        val after = before + 6 * 60 * 1000L
        val first = EdgeTtsDrm.generateSecMsGec(before)
        val second = EdgeTtsDrm.generateSecMsGec(after)
        assertEquals(false, first == second)
    }

    @Test
    fun `RFC 2616 Date is parsed as UTC unix seconds`() {
        val parsed = EdgeTtsDrm.parseRfc2616Date("Wed, 15 Nov 2023 12:00:00 GMT")
        assertNotNull(parsed)
        assertEquals(1_700_049_600.0, parsed)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.US_ASCII)).joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }
}
