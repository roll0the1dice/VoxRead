package com.roll0the1dice.voxread.desktop.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object EdgeTtsTokenV2 {

    fun hash(payload: String): String {
        val data = payload.toByteArray(StandardCharsets.US_ASCII)
        val digest = sha256Digest(data)
        val checksum = cs64Digest(data, digest)
        return "${hex(checksum)}-${hex(digest)}"
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val words = IntArray(64)
        words[14] = ((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        words[15] = ByteBuffer.wrap(data, 3, 4).int

        var index = 16
        while (index < 30) {
            var value = initialSchedule[index - 16]
            if (index - 2 >= 14) {
                val xorVal = if (index == 16) 0x000B92C0 else 0
                value += sigma1(words[index - 2]) xor xorVal
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                val xorVal = if (index == 29) 0x059C0B80 else 0
                value += sigma0(words[index - 15]) xor xorVal
            }
            words[index] = value
            index += 1
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) +
                words[14] + 0xC9DC06A7.toInt() - roundConstants[14]

        expandSchedule(words, 31)
        var state = compress(initialState, words, 14)
        var i = 0
        while (i < state.size) {
            state[i] += sha256Initial[i]
            i += 1
        }

        val length = 57 + data.size
        val padding = Math.floorMod(55 - length, 64)
        val tailBuffer = ByteBuffer.allocate(data.size - 7 + 1 + padding + 8)
        tailBuffer.put(data, 7, data.size - 7)
        tailBuffer.put(0x80.toByte())
        tailBuffer.put(ByteArray(padding))
        tailBuffer.putLong(length.toLong() * 8L)
        val tail = tailBuffer.array()

        var offset = 0
        while (offset < tail.size) {
            val block = ByteBuffer.wrap(tail, offset, 64)
            var j = 0
            while (j < 16) {
                words[j] = block.int
                j += 1
            }
            expandSchedule(words, 16)
            val working = compress(state, words, 0)
            j = 0
            while (j < state.size) {
                working[j] += state[j]
                j += 1
            }
            state = working
            offset += 64
        }

        val out = ByteBuffer.allocate(32)
        state.forEach { out.putInt(it) }
        return out.array()
    }

    private fun expandSchedule(words: IntArray, start: Int) {
        var i = start
        while (i < 64) {
            words[i] = sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]
            i += 1
        }
    }

    private fun compress(state: IntArray, words: IntArray, firstRound: Int): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        var i = firstRound
        while (i < 64) {
            val constant = if (firstRound == 14 && i == 14) 0xC9DC06A7.toInt() else roundConstants[i]
            val sum1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
            val sum0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
            val t1 = h + sum1 + (g xor (e and (f xor g))) + constant + words[i]
            val t2 = sum0 + ((a and b) or (c and (a or b)))
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
            i += 1
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun sigma0(value: Int): Int =
        Integer.rotateRight(value, 7) xor Integer.rotateRight(value, 18) xor (value ushr 3)

    private fun sigma1(value: Int): Int =
        Integer.rotateRight(value, 17) xor Integer.rotateRight(value, 19) xor (value ushr 10)

    private fun cs64Digest(data: ByteArray, digest: ByteArray): ByteArray {
        val keys = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = keys.int or 1
        val keyB = keys.int or 1
        val factors = intArrayOf(keyA * 0x82893933.toInt(), keyB * 0x82893933.toInt())
        val count = ((data.size + 1 + 7) / 8) * 2
        val input = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        input.put(0.toByte()).put(data).rewind()

        val terms = IntArray(14 + count)
        val offsets = IntArray(terms.size)
        var i = 0
        while (i < 14) {
            terms[i] = cs64Coefficients[i * 2] * factors[i % 2]
            offsets[i] = cs64Coefficients[i * 2 + 1]
            i += 1
        }
        i = 0
        while (i < count) {
            val word = input.int
            terms[14 + i] = word * (if (i % 2 == 0) keyA else keyB)
            offsets[14 + i] = Integer.rotateLeft(word, 16) * (if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (i == 0) {
                terms[14] += 0xF2F90B1A.toInt() * factors[0]
                offsets[14] += 0x0B1A0000
            }
            i += 1
        }

        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        var stepIdx = 0
        while (stepIdx < terms.size) {
            val x = terms[stepIdx]
            val y = terms[stepIdx + 1]
            var q = Integer.rotateLeft(offsets[stepIdx] + x, 16) * 0x76F50F34.toInt()
            swapAcc += Integer.rotateLeft(q, 16) * 0xC3EFEA97.toInt() + q
            q = Integer.rotateLeft(offsets[stepIdx + 1] + y, 16) * 0x1881CE3C
            swapLast = Integer.rotateLeft(q, 16) * 0x35BD1EC9 + q
            swapAcc += swapLast

            state = state * keyA + x
            state = Integer.rotateLeft(state, 16) * 0xCF98B111.toInt()
            state = Integer.rotateLeft(state, 16) * 0x87085B9F.toInt()
            state = Integer.rotateLeft(state, 16) * 0x12CEB96D
            state = Integer.rotateLeft(state, 16) * 0x257E1D83
            acc += state
            var t = state * keyB + y
            t = Integer.rotateLeft(t, 16) * 0xA27416F5.toInt()
            t = Integer.rotateLeft(t, 16) * 0xD38396FF.toInt()
            t = Integer.rotateLeft(t, 16) * 0x7C932B89
            state = Integer.rotateLeft(t, 16) * 0xBFA49F69.toInt()
            acc += state
            stepIdx += 2
        }

        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swapLast xor state).putInt(swapAcc xor acc).array()
    }

    private fun hex(bytes: ByteArray): String {
        val alphabet = "0123456789ABCDEF"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(alphabet[(b.toInt() ushr 4) and 0x0F])
            sb.append(alphabet[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private val sha256Initial = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19
    )
    private val initialState = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2
    )
    private val initialSchedule = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA, 0xA622EE8C.toInt(), 0xD1DD9491.toInt(),
        0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(), 0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060.toInt(), 0x72766564
    )
    private val roundConstants = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(), 0x3956C25B, 0x59F111F1,
        0x923F82A4.toInt(), 0xAB1C5ED5.toInt(), 0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(), 0xE49B69C1.toInt(), 0xEFBE4786.toInt(),
        0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC.toInt(), 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(), 0xC6E00BF3.toInt(), 0xD5A79147.toInt(),
        0x06CA6351, 0x14292967, 0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(), 0xA2BFE8A1.toInt(), 0xA81A664B.toInt(),
        0xC24B8B70.toInt(), 0xC76C51A3.toInt(), 0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A,
        0x5B9CCA4F, 0x682E6FF3, 0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt()
    )
    private val cs64Coefficients = intArrayOf(
        0xE82E91B1.toInt(), 0xB32700D0.toInt(), 0x44DE24C6, 0x88DFF51D.toInt(), 0x3BEB65BC, 0x29514038,
        0xC89C5933.toInt(), 0xB328CBDF.toInt(), 0x8D1EC211.toInt(), 0xD405B4D5.toInt(), 0x314EF5D5, 0x26E55F3C,
        0xE92491B1.toInt(), 0x607801C6, 0xF0A5B9D5.toInt(), 0x82B2C1FB.toInt(), 0x06064CF3, 0xFA51A3DA.toInt(),
        0xDA79FA60.toInt(), 0x207CC464, 0xBED20560.toInt(), 0x3C3C74F3, 0x89F9C9F8.toInt(), 0xD2D14B61.toInt(),
        0x35FA38C6, 0xBB04A2C1.toInt(), 0xCB49E3C6.toInt(), 0xC5626167.toInt()
    )
}
