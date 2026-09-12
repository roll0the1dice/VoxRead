/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts

import java.util.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language

@ExperimentalReadiumApi
@OptIn(ExperimentalCoroutinesApi::class)
internal class TtsEngineFacade<
    S : TtsEngine.Settings,
    P : TtsEngine.Preferences<P>,
    E : TtsEngine.Error,
    V : TtsEngine.Voice,
    >(
    private val engine: TtsEngine<S, P, E, V>,
) : Configurable<S, P> by engine {

    init {
        val listener = EngineListener()
        engine.setListener(listener)
    }

    val voices: Set<V>
        get() = engine.voices

    suspend fun speak(
        text: String,
        language: Language?,
        onStart: () -> Unit,
        onRange: (IntRange) -> Unit,
    ): E? =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { engine.stop() }
            currentTask?.continuation?.cancel()
            val id = TtsEngine.RequestId(Uuid.random().toString())
            currentTask = UtteranceTask(id, continuation, onStart, onRange)
            engine.speak(id, text, language)
        }

    fun close() {
        currentTask?.continuation?.cancel()
        engine.close()
    }

    private var currentTask: UtteranceTask<E>? = null

    private data class UtteranceTask<E : TtsEngine.Error>(
        val requestId: TtsEngine.RequestId,
        val continuation: CancellableContinuation<E?>,
        val onStart: () -> Unit,
        val onRange: (IntRange) -> Unit,
        var started: Boolean = false,
    ) {
        fun notifyStart() {
            if (!started) {
                started = true
                // Never let overlay/highlight work crash the TTS callback thread,
                // or onDone will stop firing and playback appears frozen.
                try {
                    onStart()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun getTask(id: TtsEngine.RequestId) =
        currentTask?.takeIf { it.requestId == id }

    private fun popTask(id: TtsEngine.RequestId) =
        getTask(id)
            ?.also { currentTask = null }

    private inner class EngineListener : TtsEngine.Listener<E> {

        override fun onStart(requestId: TtsEngine.RequestId) {
            getTask(requestId)?.notifyStart()
        }

        override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) {
            getTask(requestId)?.let { task ->
                // Some engines skip onStart and only report ranges.
                task.notifyStart()
                task.onRange(range)
            }
        }

        override fun onInterrupted(requestId: TtsEngine.RequestId) {
            popTask(requestId)?.continuation?.cancel()
        }

        override fun onFlushed(requestId: TtsEngine.RequestId) {
            popTask(requestId)?.continuation?.cancel()
        }

        override fun onDone(requestId: TtsEngine.RequestId) {
            popTask(requestId)?.continuation?.resume(null) { _, _, _ -> }
        }

        override fun onError(requestId: TtsEngine.RequestId, error: E) {
            popTask(requestId)?.continuation?.resume(error) { _, _, _ -> }
        }
    }
}
