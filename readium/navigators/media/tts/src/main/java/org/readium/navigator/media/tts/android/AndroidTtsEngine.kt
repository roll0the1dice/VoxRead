/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(InternalReadiumApi::class)

package org.readium.navigator.media.tts.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.*
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as AndroidVoice
import android.speech.tts.Voice.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.edge.EdgeTtsService
import org.readium.navigator.media.tts.edge.EdgeTtsVoiceStore
import org.readium.navigator.media.tts.edge.EdgeTtsVoices
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.tryOrNull
import org.readium.r2.shared.util.Language

/**
 * Default [TtsEngine] implementation using Android's native text to speech engine.
 */
@ExperimentalReadiumApi
public class AndroidTtsEngine private constructor(
    private val context: Context,
    engine: TextToSpeech,
    private val settingsResolver: SettingsResolver,
    private val voiceSelector: VoiceSelector,
    private var engineName: String?,
    override var voices: Set<Voice>,
    initialPreferences: AndroidTtsPreferences,
) : TtsEngine<
    AndroidTtsSettings,
    AndroidTtsPreferences,
    AndroidTtsEngine.Error,
    AndroidTtsEngine.Voice
    > {

    @kotlinx.serialization.Serializable
    public enum class Kind {
        System,
        Edge,
        ;

        /**
         * 🌟 关键修复 1：
         * 只要当前应用内注册了 EdgeTtsService，无论枚举是 Edge 还是 System 兜底，
         * 都强制返回自身包名，绝不返回 null（避免回退到手机厂商的系统引擎）。
         */
        public fun packageName(context: Context): String? =
            when (this) {
                Edge -> EdgeTtsService.preferredEngineName(context) ?: context.packageName
                System -> EdgeTtsService.preferredEngineName(context) ?: context.packageName
            }
    }

    public companion object {
        private const val TAG = "EdgeTtsDebug"

        public suspend operator fun invoke(
            context: Context,
            settingsResolver: SettingsResolver,
            voiceSelector: VoiceSelector,
            initialPreferences: AndroidTtsPreferences,
            engineName: String? = EdgeTtsService.preferredEngineName(context) ?: context.packageName,
        ): AndroidTtsEngine? {
            val targetEngine = engineName ?: EdgeTtsService.preferredEngineName(context) ?: context.packageName
            Log.d(TAG, "AndroidTtsEngine.invoke() 启动，目标引擎包名: $targetEngine")

            val textToSpeech = initializeTextToSpeech(context, targetEngine)
                ?: return null

            val voices = tryOrNull { textToSpeech.voices }
                ?.map { it.toTtsEngineVoice() }
                ?.toSet()
                .orEmpty()

            return AndroidTtsEngine(
                context,
                textToSpeech,
                settingsResolver,
                voiceSelector,
                targetEngine,
                voices,
                initialPreferences
            )
        }

        private suspend fun initializeTextToSpeech(
            context: Context,
            engineName: String? = EdgeTtsService.preferredEngineName(context) ?: context.packageName,
        ): TextToSpeech? {
            val target = engineName ?: EdgeTtsService.preferredEngineName(context) ?: context.packageName
            Log.d(TAG, "正在创建 TextToSpeech 实例，绑定包名: $target")
            val init = CompletableDeferred<Boolean>()

            val initListener = OnInitListener { status ->
                val success = (status == SUCCESS)
                Log.d(TAG, "TextToSpeech 初始化回调状态: success=$success (status=$status)")
                init.complete(success)
            }

            val engine = if (target != null) {
                TextToSpeech(context, initListener, target)
            } else {
                TextToSpeech(context, initListener)
            }

            return if (init.await()) engine else null
        }

        @SuppressLint("QueryPermissionsNeeded")
        public fun requestInstallVoice(context: Context) {
            val intent = Intent()
                .setAction(Engine.ACTION_INSTALL_TTS_DATA)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val availableActivities =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0)
                    )
                } else {
                    context.packageManager.queryIntentActivities(intent, 0)
                }

            if (availableActivities.isNotEmpty()) {
                context.startActivity(intent)
            }
        }

        private fun AndroidVoice.toTtsEngineVoice() =
            Voice(
                id = Voice.Id(name),
                language = Language(locale),
                quality = when (quality) {
                    QUALITY_VERY_HIGH -> Voice.Quality.Highest
                    QUALITY_HIGH -> Voice.Quality.High
                    QUALITY_NORMAL -> Voice.Quality.Normal
                    QUALITY_LOW -> Voice.Quality.Low
                    QUALITY_VERY_LOW -> Voice.Quality.Lowest
                    else -> throw IllegalStateException("Unexpected voice quality.")
                },
                requiresNetwork = isNetworkConnectionRequired
            )
    }

    public fun interface SettingsResolver {
        public fun settings(preferences: AndroidTtsPreferences): AndroidTtsSettings
    }

    public fun interface VoiceSelector {
        public fun voice(language: Language?, availableVoices: Set<Voice>): Voice?
    }

    public sealed class Error(
        override val message: String,
        override val cause: org.readium.r2.shared.util.Error? = null,
    ) : TtsEngine.Error {
        public data object Unknown : Error("An unknown error occurred.")
        public data object InvalidRequest : Error("Invalid request")
        public data object Network : Error("A network error occurred.")
        public data object NetworkTimeout : Error("Network timeout")
        public data object NotInstalledYet : Error("Voice not installed yet.")
        public data object Output : Error("An error related to the output occurred.")
        public data object Service : Error("An error occurred with the TTS service.")
        public data object Synthesis : Error("Synthesis failed.")
        public data class LanguageMissingData(val language: Language) : Error("Language data is missing.")

        public companion object {
            internal fun fromNativeError(code: Int): Error =
                when (code) {
                    ERROR_INVALID_REQUEST -> InvalidRequest
                    ERROR_NETWORK -> Network
                    ERROR_NETWORK_TIMEOUT -> NetworkTimeout
                    ERROR_NOT_INSTALLED_YET -> NotInstalledYet
                    ERROR_OUTPUT -> Output
                    ERROR_SERVICE -> Service
                    ERROR_SYNTHESIS -> Synthesis
                    else -> Unknown
                }
        }
    }

    public data class Voice(
        val id: Id,
        override val language: Language,
        val quality: Quality = Quality.Normal,
        val requiresNetwork: Boolean = false,
    ) : TtsEngine.Voice {

        @kotlinx.serialization.Serializable
        @JvmInline
        public value class Id(public val value: String)

        public enum class Quality {
            Lowest,
            Low,
            Normal,
            High,
            Highest,
        }
    }

    private data class Request(
        val id: TtsEngine.RequestId,
        val text: String,
        val language: Language?,
    )

    private sealed class State {
        data class EngineAvailable(val engine: TextToSpeech) : State()
        data class WaitingForService(val pendingRequests: MutableList<Request> = mutableListOf()) : State()
        data class Failure(val error: AndroidTtsEngine.Error) : State()
    }

    private val coroutineScope: CoroutineScope = MainScope()
    private var utteranceListener: TtsEngine.Listener<Error>? = null
    private var state: State = State.EngineAvailable(engine)
    private var isClosed: Boolean = false
    private var flushNextSpeak: Boolean = false

    override val settings: StateFlow<AndroidTtsSettings>
        field = MutableStateFlow(settingsResolver.settings(initialPreferences))
            .apply {
                engine.setupPitchAndSpeed(value)
                persistSelectedVoices(value)
            }

    override fun submitPreferences(preferences: AndroidTtsPreferences) {
        val previous = settings.value
        val newSettings = settingsResolver.settings(preferences)
        settings.value = newSettings
        persistSelectedVoices(newSettings)

        // 🌟 修复 2：防止切换到 null（小布引擎）
        val newEngineName = newSettings.engine.packageName(context) 
            ?: EdgeTtsService.preferredEngineName(context) 
            ?: context.packageName

        val voicesChanged = previous.voices != newSettings.voices
        if (newEngineName != engineName) {
            Log.w(TAG, "引擎发生改变: $engineName -> $newEngineName，执行 switchEngine")
            flushNextSpeak = true
            switchEngine(newEngineName)
            return
        }
        if (voicesChanged) {
            flushNextSpeak = true
        }
        (state as? State.EngineAvailable)?.engine?.setupPitchAndSpeed(newSettings)
    }

    override fun setListener(listener: TtsEngine.Listener<Error>?) {
        utteranceListener = listener
        (state as? State.EngineAvailable)?.let { setupListener(it.engine) }
    }

    override fun speak(
        requestId: TtsEngine.RequestId,
        text: String,
        language: Language?,
    ) {
        check(!isClosed) { "Engine is closed." }
        val request = Request(requestId, text, language)

        when (val stateNow = state) {
            is State.WaitingForService -> {
                stateNow.pendingRequests.add(request)
            }
            is State.Failure -> {
                tryReconnect(request)
            }
            is State.EngineAvailable -> {
                if (!doSpeak(stateNow.engine, request)) {
                    cleanEngine(stateNow.engine)
                    tryReconnect(request)
                }
            }
        }
    }

    override fun stop() {
        when (val stateNow = state) {
            is State.EngineAvailable -> stateNow.engine.stop()
            is State.Failure -> {}
            is State.WaitingForService -> {
                for (request in stateNow.pendingRequests) {
                    utteranceListener?.onFlushed(request.id)
                }
                stateNow.pendingRequests.clear()
            }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        coroutineScope.cancel()

        when (val stateNow = state) {
            is State.EngineAvailable -> cleanEngine(stateNow.engine)
            is State.Failure -> {}
            is State.WaitingForService -> {}
        }
    }

    private fun doSpeak(
        engine: TextToSpeech,
        request: Request,
    ): Boolean {
        val voiceName = engine.setupVoice(settings.value, request.id, request.language, voices)
            ?: return false
        val official = EdgeTtsVoices.normalize(voiceName)?.shortName
            ?: EdgeTtsVoices.sanitizeIdentifier(voiceName)
            ?: voiceName.trim()

        Log.d(TAG, "doSpeak: 最终下发 speak -> voiceName=$official, text='${request.text.take(20)}...'")

        val params = Bundle()
        if (official.isNotEmpty()) {
            EdgeTtsVoiceStore.request(official)
            params.putString(EdgeTtsVoiceStore.PARAM_SYSTEM_VOICE, official)
            params.putString(EdgeTtsVoiceStore.PARAM_EDGE_VOICE, official)
        }
        val queueMode = if (flushNextSpeak) QUEUE_FLUSH else QUEUE_ADD
        flushNextSpeak = false
        return engine.speak(request.text, queueMode, params, request.id.value) == SUCCESS
    }

    private fun persistSelectedVoices(settings: AndroidTtsSettings) {
        for ((language, voiceId) in settings.voices) {
            EdgeTtsVoices.normalize(voiceId.value)?.shortName?.let { official ->
                EdgeTtsVoiceStore.save(context, official, language, rememberAsLast = false)
            }
        }
        val chosen = preferredVoiceName(settings, settings.language)
            ?: settings.voices.values.firstOrNull()?.value
            ?: return
        EdgeTtsVoices.normalize(chosen)?.shortName?.let { official ->
            EdgeTtsVoiceStore.request(official)
            EdgeTtsVoiceStore.save(context, official, settings.language, rememberAsLast = true)
        }
    }

    private fun setupListener(engine: TextToSpeech) {
        if (utteranceListener == null) {
            engine.setOnUtteranceProgressListener(null)
        } else {
            engine.setOnUtteranceProgressListener(UtteranceListener(utteranceListener))
        }
    }

    private fun onReconnectionSucceeded(engine: TextToSpeech) {
        val previousState = state as State.WaitingForService
        setupListener(engine)
        engine.setupPitchAndSpeed(settings.value)
        state = State.EngineAvailable(engine)
        if (isClosed) {
            engine.shutdown()
        } else {
            for (request in previousState.pendingRequests) {
                doSpeak(engine, request)
            }
        }
    }

    private fun onReconnectionFailed() {
        val previousState = state as State.WaitingForService
        val error = Error.Service
        state = State.Failure(error)

        for (request in previousState.pendingRequests) {
            utteranceListener?.onError(request.id, error)
        }
    }

    private fun switchEngine(newEngineName: String?) {
        val target = newEngineName ?: EdgeTtsService.preferredEngineName(context) ?: context.packageName
        engineName = target
        when (val stateNow = state) {
            is State.EngineAvailable -> {
                stateNow.engine.stop()
                cleanEngine(stateNow.engine)
            }
            is State.WaitingForService -> {}
            is State.Failure -> {}
        }
        if (state !is State.WaitingForService) {
            state = State.WaitingForService()
        }
        coroutineScope.launch {
            val engine = initializeTextToSpeech(context, target)
            if (engine == null) {
                onReconnectionFailed()
                return@launch
            }
            voices = tryOrNull { engine.voices }
                ?.map { it.toTtsEngineVoice() }
                ?.toSet()
                .orEmpty()
            onReconnectionSucceeded(engine)
        }
    }

    private fun tryReconnect(request: Request) {
        state = State.WaitingForService(mutableListOf(request))
        coroutineScope.launch {
            initializeTextToSpeech(context, engineName)
                ?.let { onReconnectionSucceeded(it) }
                ?: onReconnectionFailed()
        }
    }

    private fun cleanEngine(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(null)
        engine.shutdown()
    }

    private fun TextToSpeech.setupPitchAndSpeed(settings: AndroidTtsSettings) {
        setSpeechRate(settings.speed.toFloat())
        setPitch(settings.pitch.toFloat())
    }

    private fun TextToSpeech.setupVoice(
        settings: AndroidTtsSettings,
        id: TtsEngine.RequestId,
        utteranceLanguage: Language?,
        voices: Set<Voice>,
    ): String? {
        val language = utteranceLanguage
            .takeUnless { settings.overrideContentLanguage }
            ?.takeIf { isLanguageAvailable(it.locale) != LANG_NOT_SUPPORTED }
            ?: settings.language
                .takeIf { isLanguageAvailable(it.locale) != LANG_NOT_SUPPORTED }
            ?: defaultVoice?.locale?.let { Language(it) }

        if (language == null) {
            utteranceListener?.onError(id, Error.Unknown)
            return null
        }

        if (isLanguageAvailable(language.locale) < LANG_AVAILABLE) {
            utteranceListener?.onError(id, Error.LanguageMissingData(language))
            return null
        }

        val preferredVoiceId = preferredVoiceName(settings, language)

        val androidVoice = preferredVoiceId?.let { voiceForName(it) }
            ?: run {
                voiceSelector
                    .voice(language, voices)
                    ?.let { voiceForName(it.id.value) }
            }
            ?: preferredVoiceFor(language)

        androidVoice
            ?.let { this.voice = it }
            ?: run { this.language = language.locale }

        return preferredVoiceId
            ?: EdgeTtsVoices.normalize(androidVoice?.name)?.shortName
            ?: androidVoice?.name.orEmpty()
    }

    private fun preferredVoiceName(settings: AndroidTtsSettings, language: Language): String? {
        val raw = settings.voices[language]?.value
            ?: settings.voices[language.removeRegion()]?.value
            ?: settings.voices.entries.firstOrNull { (key, _) ->
                key.code.equals(language.code, ignoreCase = true) ||
                    key.removeRegion().code.equals(
                        language.removeRegion().code,
                        ignoreCase = true
                    )
            }?.value?.value
            ?: settings.voices.values.firstOrNull()?.value
            ?: EdgeTtsVoiceStore.requested()
            ?: EdgeTtsVoiceStore.get(context, language.locale)
            ?: EdgeTtsVoiceStore.last(context)
        return EdgeTtsVoices.normalize(raw)?.shortName
    }

    private fun TextToSpeech.voiceForName(name: String): AndroidVoice? {
        val official = EdgeTtsVoices.normalize(name)?.shortName ?: name
        return voices.firstOrNull { it.name == official }
            ?: voices.firstOrNull { it.name.equals(official, ignoreCase = true) }
    }

    private fun TextToSpeech.preferredVoiceFor(language: Language): AndroidVoice? {
        val nativeVoices = tryOrNull { voices }.orEmpty()
        val matching = nativeVoices.filter {
            Language(it.locale).removeRegion() == language.removeRegion()
        }
        val candidates = matching.ifEmpty { nativeVoices }
        return candidates.maxByOrNull { it.quality }
    }

    private class UtteranceListener(
        private val listener: TtsEngine.Listener<Error>?,
    ) : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            listener?.onStart(TtsEngine.RequestId(utteranceId))
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            listener?.let {
                val requestId = TtsEngine.RequestId(utteranceId)
                if (interrupted) {
                    it.onInterrupted(requestId)
                } else {
                    it.onFlushed(requestId)
                }
            }
        }

        override fun onDone(utteranceId: String) {
            listener?.onDone(TtsEngine.RequestId(utteranceId))
        }

        @Deprecated(
            "Deprecated in the interface",
            ReplaceWith("onError(utteranceId, -1)"),
            level = DeprecationLevel.ERROR
        )
        override fun onError(utteranceId: String) {
            onError(utteranceId, -1)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            listener?.onError(
                TtsEngine.RequestId(utteranceId),
                Error.fromNativeError(errorCode)
            )
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            listener?.onRange(TtsEngine.RequestId(utteranceId), start until end)
        }
    }
}