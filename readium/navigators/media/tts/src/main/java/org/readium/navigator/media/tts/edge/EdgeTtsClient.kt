/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber

/**
 * OkHttp WebSocket client for a single Edge Read Aloud turn.
 *
 * After [connect] the caller sends `speech.config` then SSML via [sendSpeechConfig]
 * and [sendSsml]. Incoming frames are offered on [events].
 */
internal class EdgeTtsClient(
    private val httpClient: OkHttpClient,
) : AutoCloseable {

    sealed class Event {
        data object Opened : Event()
        class Mp3(val bytes: ByteArray) : Event()
        data class Metadata(val json: String) : Event()
        data object TurnEnd : Event()
        data class Failed(val forbidden: Boolean, val message: String, val httpDate: String?) : Event()
        data object Closed : Event()
    }

    val events: LinkedBlockingQueue<Event> = LinkedBlockingQueue()

    private val cancelled = AtomicBoolean(false)
    private var webSocket: WebSocket? = null

    fun connect() {
        val url = buildUrl()
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", EdgeTtsConstants.ORIGIN)
            .header("User-Agent", EdgeTtsConstants.USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${randomHex(16)};")
            .build()
        webSocket = httpClient.newWebSocket(request, Listener())
    }

    fun sendSpeechConfig() {
        val socket = webSocket ?: return
        socket.send(EdgeTtsSsml.speechConfigFrame(javascriptDate()))
    }

    fun sendSsml(ssml: String) {
        val socket = webSocket ?: return
        val voiceName = VOICE_NAME_IN_SSML.find(ssml)?.groupValues?.getOrNull(1)
        val xmlLang = XML_LANG_IN_SSML.find(ssml)?.groupValues?.getOrNull(1)
        Timber.i("Edge TTS outgoing SSML <voice name=\"%s\"> xml:lang='%s'", voiceName, xmlLang)
        socket.send(EdgeTtsSsml.ssmlFrame(connectId(), javascriptDate(), ssml))
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return
        }
        webSocket?.cancel()
        events.offer(Event.Closed)
    }

    override fun close() {
        cancel()
        webSocket = null
    }

    fun poll(timeoutMs: Long): Event? =
        events.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            events.offer(Event.Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = EdgeTtsAudioParser.parseTextMessage(text)
            when (message.path) {
                "audio.metadata" -> events.offer(Event.Metadata(message.body))
                "turn.end" -> events.offer(Event.TurnEnd)
                "turn.start", "response" -> Unit
                else -> Timber.d("Edge TTS text path=${message.path}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val mp3 = EdgeTtsAudioParser.extractMp3(bytes.toByteArray()) ?: return
            if (mp3.isNotEmpty()) {
                events.offer(Event.Mp3(mp3))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val forbidden = response?.code == 403
            val message = response?.message?.takeIf { it.isNotBlank() } ?: t.message ?: "WebSocket failed"
            events.offer(
                Event.Failed(
                    forbidden = forbidden,
                    message = message,
                    httpDate = response?.header("Date")
                )
            )
            if (t !is IOException) {
                Timber.e(t, "Edge TTS WebSocket failure: $message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.offer(Event.Closed)
        }
    }

    companion object {

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()

        fun buildUrl(): String {
            val connectionId = connectId()
            val token = EdgeTtsDrm.generateSecMsGec()
            return EdgeTtsConstants.WSS_URL +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=$token" +
                "&Sec-MS-GEC-Version=${EdgeTtsConstants.SEC_MS_GEC_VERSION}"
        }

        private val VOICE_NAME_IN_SSML: Regex = Regex("<voice name='([^']*)'>")
        private val XML_LANG_IN_SSML: Regex = Regex("xml:lang='([^']*)'")

        fun connectId(): String =
            UUID.randomUUID().toString().replace("-", "")

        fun javascriptDate(now: Date = Date()): String {
            val formatter = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter.format(now) + " GMT+0000 (Coordinated Universal Time)"
        }

        private fun randomHex(bytes: Int): String {
            val alphabet = "0123456789ABCDEF"
            return buildString(bytes * 2) {
                repeat(bytes * 2) {
                    append(alphabet.random())
                }
            }
        }
    }
}
