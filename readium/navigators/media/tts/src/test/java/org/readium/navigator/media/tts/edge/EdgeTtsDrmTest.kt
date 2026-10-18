/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class EdgeTtsDrmTest {

    @Before
    @After
    fun resetClockSkew() {
        EdgeTtsDrm.adjustClockSkewSeconds(-EdgeTtsDrm.clockSkewSeconds())
    }

    @Test
    fun `v2 tokens match edge-tts 7_3_0 reference vectors`() {
        // Fixed reference outputs, including the Edge capture reported in upstream #490.
        val vectors = mapOf(
            0L to "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            1_700_000_000_000L to "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            1_791_888_000_000L to "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            1_792_293_456_789L to "1D112848F7AFE780-D6F31CC1C8A958E759E3848BC6FEBADFF565DF3767F159E20DFBB86F3F37E4E5",
            1_893_456_000_000L to "7442B6E630CDF0DE-CE82EC1509335DC856270C1EEB0F7F2575909AC26840D2C15B28A41EBB7E9CCF"
        )
        vectors.forEach { (millis, expected) ->
            assertEquals(expected, EdgeTtsDrm.generateSecMsGec(millis), "timestamp=$millis")
        }
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

    @Test
    fun `clock correction is applied to the v2 token`() {
        val timestamp = 1_791_888_000_000L
        val expected = EdgeTtsDrm.generateSecMsGec(timestamp)
        EdgeTtsDrm.adjustClockSkewSeconds(300.0)
        assertEquals(expected, EdgeTtsDrm.generateSecMsGec(timestamp - 300_000L))
    }

    @Test
    fun `websocket handshake pairs v2 token with v2 version`() {
        val url = EdgeTtsClient.buildUrl()
        assertTrue(Regex("[&]Sec-MS-GEC=[A-F0-9]{16}-[A-F0-9]{64}[&]").containsMatchIn(url))
        assertTrue(url.endsWith("&Sec-MS-GEC-Version=2-143.0.3650.75"))
    }
}
