/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 16-bit Little-Endian PCM 实时动态范围压缩器（DRC）与音频增益放大器：
 * 用于将 Edge TTS 合成的原始语音响度（约 -22 LUFS）拉升并均衡至主流流媒体视频标准（如 YouTube -14 LUFS），
 * 同时通过软削波限幅器（Soft-Knee Tanh Limiter）保证高倍率增益下绝不破音。
 */
internal class EdgeTtsPcmProcessor(
    gainFactor: Double = EdgeTtsConstants.DEFAULT_VOLUME_FACTOR,
    private val drcEnabled: Boolean = EdgeTtsConstants.DEFAULT_DRC_ENABLED,
) {
    private val clampedGain: Double = gainFactor.coerceIn(0.1, 3.0)

    // 24kHz 采样率下的包络跟踪系数（Attack ≈ 4ms, Release ≈ 80ms）
    private val attackCoeff: Double = 0.01
    private val releaseCoeff: Double = 0.0005

    // DRC 阈值与目标补偿增益
    private val drcThreshold: Double = 0.22 // 约 -13 dBFS
    private val noiseGate: Double = 0.008   // 约 -42 dBFS 底噪门限
    private val drcMakeupGain: Double = if (drcEnabled) 1.55 else 1.0

    private var envelope: Double = 0.0

    fun processInPlace(pcm16Le: ByteArray) {
        if (!drcEnabled && abs(clampedGain - 1.0) < 0.02) {
            return
        }
        val sampleCount = pcm16Le.size / 2
        for (i in 0 until sampleCount) {
            val byteIdx = i * 2
            val lo = pcm16Le[byteIdx].toInt() and 0xFF
            val hi = pcm16Le[byteIdx + 1].toInt() // signed
            val rawShort = (hi shl 8) or lo
            val x = rawShort / 32768.0
            val absX = abs(x)

            // 更新包络（快攻慢放）
            envelope = if (absX > envelope) {
                envelope + attackCoeff * (absX - envelope)
            } else {
                envelope + releaseCoeff * (absX - envelope)
            }

            // 动态范围压缩（DRC）：抬升轻音音节，压制过高尖峰
            val drcGain = if (drcEnabled && envelope > noiseGate) {
                if (envelope > drcThreshold) {
                    // 超过阈值按 3:1 比例压缩
                    val compressedEnv = drcThreshold + (envelope - drcThreshold) * 0.33
                    (compressedEnv / envelope) * drcMakeupGain
                } else {
                    // 低于阈值的轻音获得平滑拉升
                    drcMakeupGain
                }
            } else {
                1.0
            }

            var y = x * drcGain * clampedGain

            // 软拐点限幅器（Soft-Knee Limiter）：超过 0.80 幅度时平滑渐近 0.99，彻底消除削波失真
            val absY = abs(y)
            if (absY > 0.80) {
                val sign = if (y >= 0.0) 1.0 else -1.0
                val excess = (absY - 0.80) / 0.19
                y = sign * (0.80 + 0.19 * tanh(excess))
            }

            val outInt = (y * 32767.0).roundToInt().coerceIn(-32768, 32767)
            pcm16Le[byteIdx] = (outInt and 0xFF).toByte()
            pcm16Le[byteIdx + 1] = ((outInt ushr 8) and 0xFF).toByte()
        }
    }
}
