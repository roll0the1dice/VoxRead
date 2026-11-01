package com.roll0the1dice.voxread.desktop.tts

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TTS 音频响度增强处理器（桌面端）：
 * 1. 将用户增益系数映射为 Edge TTS SSML `<prosody volume='...'>` 参数；
 * 2. 在播放端对 MP3 帧的 `global_gain`（每步 1.505 dB）执行无损动态范围压缩（DRC）与增益放大，
 *    将 TTS 原始人声（约 -22 LUFS）拉升并均衡至主流流媒体视频标准（如 YouTube -14 LUFS），同时防止削波破音。
 */
object TtsAudioProcessor {

    /** MP3 global_gain 每 +1 对应的分贝增量：20 * log10(2^(1/4)) ≈ 1.50515 dB */
    private const val DB_PER_GAIN_STEP = 1.505149978319906

    /** 防止解码器 16-bit PCM 削波溢出的安全上限（预留峰值余量） */
    private const val MAX_SAFE_GLOBAL_GAIN = 222

    /** 最小有效语音帧 global_gain（低于此值视为静音/底噪帧，不主动拉升） */
    private const val MIN_ACTIVE_GLOBAL_GAIN = 80

    /** 开启 DRC 时的基准响度对齐补偿（+6.0 dB，配合 SSML +100% 对齐 YouTube -14 LUFS） */
    private const val DRC_MAKEUP_DB = 6.0

    private val BITRATES_MPEG1_L3 = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    )
    private val BITRATES_MPEG2_L3 = intArrayOf(
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    )
    private val SAMPLE_RATES_MPEG1 = intArrayOf(44100, 48000, 32000, 0)
    private val SAMPLE_RATES_MPEG2 = intArrayOf(22050, 24000, 16000, 0)
    private val SAMPLE_RATES_MPEG25 = intArrayOf(11025, 12000, 8000, 0)

    /**
     * 将增益倍率（0.5x ~ 3.0x）转换为 Edge TTS SSML 的 `volume` 属性值。
     * 当 gainFactor >= 1.5 时，SSML 侧直接请求最大合成响度 `+100%`；
     * 当 1.0 <= gainFactor < 1.5 时，按比例映射为 `+0%` ~ `+100%`；
     * 当 gainFactor < 1.0 时，映射为负百分比（如 0.5 -> `-50%`）。
     */
    fun gainToSsmlVolume(gainFactor: Double): String {
        val clamped = gainFactor.coerceIn(0.1, 3.0)
        val pct = when {
            clamped >= 1.5 -> 100
            clamped >= 1.0 -> ((clamped - 1.0) * 200.0).roundToInt().coerceIn(0, 100)
            else -> ((clamped - 1.0) * 100.0).roundToInt().coerceIn(-90, 0)
        }
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    /**
     * 计算当前增益与 DRC 状态相对于原始默认语音（1.0x 无 DRC）的等效总增益分贝数（用于 UI 展示）。
     */
    fun effectiveDecibels(gainFactor: Double, drcEnabled: Boolean): Double {
        val clamped = gainFactor.coerceIn(0.1, 3.0)
        val userDb = 20.0 * log10(clamped)
        val drcDb = if (drcEnabled) DRC_MAKEUP_DB else 0.0
        return userDb + drcDb
    }

    /**
     * 计算需要附加在 JavaFX AudioEqualizer 上的辅助频段增益（-24.0 ~ +12.0 dB）。
     */
    fun equalizerBandGainDb(gainFactor: Double, drcEnabled: Boolean, bandIndex: Int, totalBands: Int): Double {
        val clamped = gainFactor.coerceIn(0.1, 3.0)
        val baseDb = if (clamped > 1.0) (20.0 * log10(clamped)).coerceIn(0.0, 9.5) else 0.0
        val drcPresenceBoost = if (drcEnabled) {
            // 对人声核心清晰度频段（中高频 1kHz ~ 4kHz）给予额外 +2.5dB 穿透力提升
            val ratio = if (totalBands > 1) bandIndex.toDouble() / (totalBands - 1).toDouble() else 0.5
            if (ratio in 0.4..0.85) 2.5 else 1.2
        } else 0.0
        return (baseDb + drcPresenceBoost).coerceIn(-24.0, 12.0)
    }

    private data class GranuleGainLocation(
        val sideInfoByteOffset: Int,
        val gainBitOffset: Int,
        val originalGain: Int
    )

    /**
     * 对 MP3 字节流执行双遍（Two-Pass）无损 DRC 动态范围压缩与增益放大：
     * - 第一遍扫描整句所有 MP3 帧的 granule `global_gain`，定位峰值与动态分布；
     * - 第二遍对弱音音节进行动态范围压缩拉升（DRC），并叠加目标增益与防削波限幅（Limiter）。
     */
    fun processMp3WithDrcAndGain(
        mp3Bytes: ByteArray,
        gainFactor: Double = EdgeTtsConstants.DEFAULT_GAIN_FACTOR,
        drcEnabled: Boolean = EdgeTtsConstants.DEFAULT_DRC_ENABLED
    ): ByteArray {
        if (mp3Bytes.size < 16) return mp3Bytes
        val clampedGain = gainFactor.coerceIn(0.1, 3.0)
        if (!drcEnabled && kotlin.math.abs(clampedGain - 1.0) < 0.02) {
            return mp3Bytes
        }

        val out = mp3Bytes.copyOf()
        val locations = scanGranuleGains(out)
        if (locations.isEmpty()) return out

        val activeGains = locations
            .map { it.originalGain }
            .filter { it >= MIN_ACTIVE_GLOBAL_GAIN }
            .sorted()

        if (activeGains.isEmpty()) return out

        val peakGain = activeGains.last()
        // 取 80% 分位作为语句基准响度参考点
        val refGain = activeGains[(activeGains.size * 0.80).toInt().coerceIn(0, activeGains.lastIndex)]

        // 计算用户请求的附加增益步数（每步约 1.505 dB）
        // 注意：SSML 侧已经承担了部分基础增益，播放端再叠加剩余增益与 DRC 补偿
        val userDb = 20.0 * log10(clampedGain)
        val totalTargetDb = userDb + (if (drcEnabled) DRC_MAKEUP_DB else 0.0)
        val requestedSteps = (totalTargetDb / DB_PER_GAIN_STEP).roundToInt()

        // 防削波余量限制：确保整句最高峰不会超过 MAX_SAFE_GLOBAL_GAIN
        val maxAllowedBoostSteps = (MAX_SAFE_GLOBAL_GAIN - peakGain).coerceAtLeast(0)
        val baseSteps = if (requestedSteps >= 0) {
            min(requestedSteps, maxAllowedBoostSteps)
        } else {
            requestedSteps
        }

        for (loc in locations) {
            val orig = loc.originalGain
            if (orig < MIN_ACTIVE_GLOBAL_GAIN) continue

            // DRC 动态范围压缩：将低于参考响度的轻音帧按 2.5:1 比例向参考响度靠拢，消除忽大忽小感
            val drcBoostSteps = if (drcEnabled && orig < refGain && orig >= peakGain - 28) {
                val deficit = refGain - orig
                ((deficit * 0.60).roundToInt()).coerceIn(0, 8)
            } else {
                0
            }

            val newGain = (orig + baseSteps + drcBoostSteps).coerceIn(1, MAX_SAFE_GLOBAL_GAIN)
            if (newGain != orig) {
                writeBits(out, loc.sideInfoByteOffset, loc.gainBitOffset, 8, newGain)
            }
        }

        return out
    }

    private fun scanGranuleGains(data: ByteArray): List<GranuleGainLocation> {
        val result = ArrayList<GranuleGainLocation>(128)
        var pos = skipId3v2(data)
        val limit = data.size - 4

        while (pos <= limit) {
            val b0 = data[pos].toInt() and 0xFF
            val b1 = data[pos + 1].toInt() and 0xFF

            // 检查 11-bit 帧同步字 0xFFE
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) {
                pos += 1
                continue
            }

            val versionBits = (b1 ushr 3) and 0x03
            val layerBits = (b1 ushr 1) and 0x03
            val protectionBit = b1 and 0x01

            // 仅处理 MPEG-1/2/2.5 Layer III (layerBits == 1)
            if (versionBits == 1 || layerBits != 1) {
                pos += 1
                continue
            }

            val b2 = data[pos + 2].toInt() and 0xFF
            val b3 = data[pos + 3].toInt() and 0xFF

            val bitrateIdx = (b2 ushr 4) and 0x0F
            val srIdx = (b2 ushr 2) and 0x03
            val paddingBit = (b2 ushr 1) and 0x01
            val channelMode = (b3 ushr 6) and 0x03

            if (bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) {
                pos += 1
                continue
            }

            val isMpeg1 = (versionBits == 3)
            val bitrateKbps = if (isMpeg1) BITRATES_MPEG1_L3[bitrateIdx] else BITRATES_MPEG2_L3[bitrateIdx]
            val sampleRateHz = when (versionBits) {
                3 -> SAMPLE_RATES_MPEG1[srIdx]
                2 -> SAMPLE_RATES_MPEG2[srIdx]
                else -> SAMPLE_RATES_MPEG25[srIdx]
            }

            if (bitrateKbps <= 0 || sampleRateHz <= 0) {
                pos += 1
                continue
            }

            val frameSize = if (isMpeg1) {
                (144 * bitrateKbps * 1000) / sampleRateHz + paddingBit
            } else {
                (72 * bitrateKbps * 1000) / sampleRateHz + paddingBit
            }

            if (frameSize < 24 || pos + frameSize > data.size) {
                break
            }

            // 仅在无 CRC 校验（protectionBit == 1）时直接修改 side info，避免破坏 CRC
            if (protectionBit == 1) {
                val channels = if (channelMode == 3) 1 else 2
                val sideInfoSize = if (isMpeg1) {
                    if (channels == 1) 17 else 32
                } else {
                    if (channels == 1) 9 else 17
                }

                val sideInfoOffset = pos + 4
                if (sideInfoOffset + sideInfoSize <= pos + frameSize) {
                    val baseBits = if (isMpeg1) {
                        9 + (if (channels == 1) 5 else 3) + 4 * channels
                    } else {
                        8 + (if (channels == 1) 1 else 2)
                    }
                    val granules = if (isMpeg1) 2 else 1
                    val granuleChannelBits = if (isMpeg1) 59 else 63

                    for (gr in 0 until granules) {
                        for (ch in 0 until channels) {
                            val gcBitOffset = baseBits + (gr * channels + ch) * granuleChannelBits
                            val part23Length = readBits(data, sideInfoOffset, gcBitOffset, 12)
                            val gainBitOffset = gcBitOffset + 21
                            val globalGain = readBits(data, sideInfoOffset, gainBitOffset, 8)
                            if (part23Length > 0 && globalGain > 0) {
                                result.add(GranuleGainLocation(sideInfoOffset, gainBitOffset, globalGain))
                            }
                        }
                    }
                }
            }

            pos += frameSize
        }

        return result
    }

    private fun skipId3v2(data: ByteArray): Int {
        if (data.size >= 10 &&
            data[0] == 'I'.code.toByte() &&
            data[1] == 'D'.code.toByte() &&
            data[2] == '3'.code.toByte()
        ) {
            val size = ((data[6].toInt() and 0x7F) shl 21) or
                ((data[7].toInt() and 0x7F) shl 14) or
                ((data[8].toInt() and 0x7F) shl 7) or
                (data[9].toInt() and 0x7F)
            val total = 10 + size
            if (total < data.size) return total
        }
        return 0
    }

    private fun readBits(data: ByteArray, byteBase: Int, bitOffset: Int, numBits: Int): Int {
        var result = 0
        for (i in 0 until numBits) {
            val totalBit = bitOffset + i
            val b = data[byteBase + (totalBit ushr 3)].toInt() and 0xFF
            val bit = (b ushr (7 - (totalBit and 7))) and 1
            result = (result shl 1) or bit
        }
        return result
    }

    private fun writeBits(data: ByteArray, byteBase: Int, bitOffset: Int, numBits: Int, value: Int) {
        for (i in 0 until numBits) {
            val totalBit = bitOffset + i
            val byteIdx = byteBase + (totalBit ushr 3)
            val bitPos = 7 - (totalBit and 7)
            val bit = (value ushr (numBits - 1 - i)) and 1
            val cur = data[byteIdx].toInt() and 0xFF
            data[byteIdx] = (if (bit == 1) (cur or (1 shl bitPos)) else (cur and (1 shl bitPos).inv())).toByte()
        }
    }
}
