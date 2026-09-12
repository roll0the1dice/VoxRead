/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.readium.r2.shared.ExperimentalReadiumApi
import timber.log.Timber

/**
 * System [TextToSpeechService] backed by Microsoft Edge Read Aloud.
 *
 * Register this service with `android.intent.action.TTS_SERVICE` so it appears
 * in the device TTS settings and can be selected by [TextToSpeech].
 */
@ExperimentalReadiumApi
public class EdgeTtsService : TextToSpeechService() {

    public companion object {
        private const val TAG = "EdgeTtsDebug"

        /**
         * Returns the host app package name when this service is registered,
         * so [TextToSpeech] can bind to it instead of the system default engine.
         */
        @SuppressLint("QueryPermissionsNeeded")
        public fun preferredEngineName(context: Context): String? {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .setPackage(context.packageName)
            val resolved =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.queryIntentServices(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0)
                    )
                } else {
                    context.packageManager.queryIntentServices(intent, 0)
                }
            return context.packageName.takeIf { resolved.isNotEmpty() }
        }

        /**
         * 归一化语言代码，把 Android 底层可能传入的 3 位 ISO 代码映射为 2 位代码。
         */
        public fun normalizeLangCode(lang: String?): String {
            return when (lang?.lowercase(Locale.ROOT)) {
                "zho", "chi", "cmn", "zh" -> "zh"
                "eng", "en" -> "en"
                "jpn", "ja" -> "ja"
                "kor", "ko" -> "ko"
                "fra", "fre", "fr" -> "fr"
                "deu", "ger", "de" -> "de"
                "spa", "es" -> "es"
                "rus", "ru" -> "ru"
                "ita", "it" -> "it"
                else -> lang.orEmpty().lowercase(Locale.ROOT)
            }
        }

        private fun normalizeLanguageTag(language: String?, country: String?): Locale {
            val lang = normalizeLangCode(language)
            return try {
                Locale.Builder()
                    .setLanguage(lang)
                    .setRegion(country.orEmpty())
                    .build()
            } catch (_: Exception) {
                Locale.getDefault()
            }
        }
    }

    private val synthesizer = EdgeTtsSynthesizer()

    @Volatile
    private var currentSessionCancelled: AtomicBoolean? = null

    @Volatile
    private var currentLatch: CountDownLatch? = null

    private var loadedLanguage: String = "zh"
    private var loadedCountry: String = "CN"
    private var loadedVariant: String = ""
    private var loadedVoiceName: String? = null

    public override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== EdgeTtsService 启动 onCreate ===")
        EdgeTtsVoiceCatalog.refreshAsync()
    }

    /**
     * 🌟 关键修复：防止系统因 3 位 ISO 语言代码（如 zho）误判为 LANG_NOT_SUPPORTED
     */
    public override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val normLang = normalizeLangCode(lang)
        val result = EdgeTtsVoices.availability(normLang, country, variant)
        if (result != TextToSpeech.LANG_NOT_SUPPORTED) {
            return result
        }
        // 安全兜底：对中英文提供默认保障支持
        return if (normLang == "zh" || normLang == "en") {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            EdgeTtsVoices.availability(lang, country, variant)
        }
    }

    public override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result != TextToSpeech.LANG_NOT_SUPPORTED) {
            loadedLanguage = normalizeLangCode(lang)
            loadedCountry = country.orEmpty()
            loadedVariant = variant.orEmpty()
        }
        return result
    }

    public override fun onGetLanguage(): Array<String> =
        arrayOf(loadedLanguage, loadedCountry, loadedVariant)

    public override fun onGetVoices(): List<Voice> =
        EdgeTtsVoices.all.map(EdgeTtsVoices::toAndroidVoice)

    public override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val locale = normalizeLanguageTag(lang, country)
        val defaultName = EdgeTtsVoices.normalize(EdgeTtsVoiceStore.get(this, locale))?.shortName
            ?: EdgeTtsVoices.normalize(loadedVoiceName)?.shortName
            ?: EdgeTtsVoices.defaultFor(locale).shortName
        Log.d(TAG, "onGetDefaultVoiceNameFor($lang, $country) -> $defaultName")
        return defaultName
    }

    public override fun onIsValidVoiceName(voiceName: String?): Int =
        if (EdgeTtsVoices.normalize(voiceName) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    public override fun onLoadVoice(voiceName: String?): Int {
        val entry = EdgeTtsVoices.normalize(voiceName) ?: return TextToSpeech.ERROR
        loadedVoiceName = entry.shortName
        loadedLanguage = entry.locale.language
        loadedCountry = entry.locale.country
        loadedVariant = ""
        Log.i(TAG, "onLoadVoice: 系统加载音色 -> ${entry.shortName}")
        return TextToSpeech.SUCCESS
    }

    public override fun onStop() {
        Log.d(TAG, "onStop: 收到中止播放指令")
        currentSessionCancelled?.set(true)
        synthesizer.cancel()
        currentLatch?.countDown() // 避免线程死锁挂起
    }

    public override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val cancelled = AtomicBoolean(false)
        currentSessionCancelled = cancelled

        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            callback.start(
                EdgeTtsConstants.SAMPLE_RATE_HZ,
                AudioFormat.ENCODING_PCM_16BIT,
                EdgeTtsConstants.CHANNEL_COUNT
            )
            callback.done()
            return
        }

        val locale = normalizeLanguageTag(request.language, request.country)
        val voice = resolveVoice(request, locale)

        val logMsg = ">>> [发起 Edge-TTS 合成] voice=${voice.shortName}, xml:lang=${EdgeTtsVoices.xmlLang(voice)}, text='${text.take(30)}...'"
        Log.i(TAG, logMsg)
        Timber.i(logMsg)

        val rate = (request.speechRate / 100.0).coerceIn(0.1, 3.0)
        val pitch = (request.pitch / 100.0).coerceIn(0.1, 2.0)

        val startStatus = callback.start(
            EdgeTtsConstants.SAMPLE_RATE_HZ,
            AudioFormat.ENCODING_PCM_16BIT,
            EdgeTtsConstants.CHANNEL_COUNT
        )
        if (startStatus != TextToSpeech.SUCCESS) {
            Log.e(TAG, "callback.start 失败: $startStatus")
            return
        }

        val maxBuffer = callback.maxBufferSize.coerceAtLeast(1)
        val completionLatch = CountDownLatch(1)
        currentLatch = completionLatch

        try {
            synthesizer.synthesize(
                request = EdgeTtsSynthesizer.Request(
                    text = text,
                    voice = voice.shortName,
                    rate = rate,
                    pitch = pitch
                ),
                cancelled = cancelled,
                listener = object : EdgeTtsSynthesizer.Listener {
                    override fun onPcm(pcm: ByteArray): Boolean {
                        if (cancelled.get()) {
                            return false
                        }
                        var offset = 0
                        while (offset < pcm.size) {
                            val length = minOf(maxBuffer, pcm.size - offset)
                            val status = callback.audioAvailable(pcm, offset, length)
                            if (status != TextToSpeech.SUCCESS) {
                                return false
                            }
                            offset += length
                        }
                        return true
                    }

                    override fun onRange(start: Int, end: Int, frame: Int) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            callback.rangeStart(frame, start, end)
                        }
                    }

                    override fun onDone() {
                        Log.d(TAG, "=== 本句音频传输完成 (${voice.shortName}) ===")
                        try {
                            callback.done()
                        } finally {
                            completionLatch.countDown()
                        }
                    }

                    override fun onError(network: Boolean, message: String) {
                        Log.e(TAG, "Edge TTS 合成错误 (network=$network): $message")
                        Timber.e("Edge TTS synthesis error: $message")
                        try {
                            if (network) {
                                callback.error(TextToSpeech.ERROR_NETWORK)
                            } else {
                                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                            }
                        } finally {
                            completionLatch.countDown()
                        }
                    }
                }
            )

            // 同步阻塞等待，最长 45 秒防超时卡死
            completionLatch.await(45, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "合成线程被打断: ${e.message}")
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        } finally {
            currentLatch = null
        }
    }

    private fun resolveVoice(
        request: SynthesisRequest,
        locale: Locale,
    ): EdgeTtsVoices.Entry {
        val implicitDefault = EdgeTtsVoices.defaultFor(locale).shortName
        val stored = EdgeTtsVoiceStore.get(this, locale) ?: EdgeTtsVoiceStore.last(this)

        val customEdgeParam = request.params?.getString(EdgeTtsVoiceStore.PARAM_EDGE_VOICE)
        val systemParam = request.params?.getString(EdgeTtsVoiceStore.PARAM_SYSTEM_VOICE)
        val requestVoice = request.voiceName
        val globalRequested = EdgeTtsVoiceStore.requested()

        Log.d(TAG, "resolveVoice: params=[edge=$customEdgeParam, sys=$systemParam, reqVoice=$requestVoice], stored=$stored, default=$implicitDefault")

        val chosen = listOf(
            globalRequested,
            customEdgeParam,
            requestVoice,
            systemParam,
            stored,
            loadedVoiceName
        )

        for ((index, name) in chosen.withIndex()) {
            val entry = EdgeTtsVoices.normalize(name) ?: continue

            val fromSystemDefault = (index == 2 || index == 3) &&
                entry.shortName == implicitDefault &&
                stored != null &&
                stored != implicitDefault

            if (fromSystemDefault) {
                Log.w(TAG, "跳过系统默认音色 [index=$index] $name, 保持使用存储音色: $stored")
                continue
            }

            loadedVoiceName = entry.shortName
            Log.i(TAG, "🎯 最终判定音色: [index=$index] -> ${entry.shortName}")
            return entry
        }

        val fallback = EdgeTtsVoices.normalize(stored) ?: EdgeTtsVoices.defaultFor(locale)
        Log.w(TAG, "⚠️ 触发兜底音色 -> ${fallback.shortName}")
        return fallback
    }
}