package com.roll0the1dice.voxread.desktop.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class WordBoundary(
    val offsetMs: Double,
    val durationMs: Double,
    val text: String,
    val startChar: Int,
    val endChar: Int
)

data class SynthesisResult(
    val audioBytes: ByteArray,
    val wordBoundaries: List<WordBoundary>
)

class EdgeTtsClient(private val client: OkHttpClient = defaultClient) {

    fun synthesize(
        text: String,
        voice: String = EdgeTtsConstants.DEFAULT_VOICE,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = EdgeTtsConstants.DEFAULT_VOLUME
    ): CompletableFuture<ByteArray> =
        synthesizeWithMetadata(text, voice, rate, pitch, volume).thenApply { it.audioBytes }

    fun synthesizeWithMetadata(
        text: String,
        voice: String = EdgeTtsConstants.DEFAULT_VOICE,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = EdgeTtsConstants.DEFAULT_VOLUME
    ): CompletableFuture<SynthesisResult> {
        val future = CompletableFuture<SynthesisResult>()
        val audioStream = ByteArrayOutputStream()
        val boundaries = mutableListOf<WordBoundary>()
        var searchCursor = 0

        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val token = EdgeTtsDrm.generateSecMsGec()
        val url = "${EdgeTtsConstants.WSS_URL}&ConnectionId=$connectionId&Sec-MS-GEC=$token&Sec-MS-GEC-Version=${EdgeTtsConstants.SEC_MS_GEC_VERSION}"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", EdgeTtsConstants.ORIGIN)
            .header("User-Agent", EdgeTtsConstants.USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        val dateStr = javascriptDate()
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val ssml = """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>
            <voice name='$voice'>
            <prosody pitch='$pitch' rate='$rate' volume='$volume'>
            ${escapeXml(text)}
            </prosody>
            </voice>
            </speak>
        """.trimIndent()

        val speechConfigMsg =
            "X-Timestamp:$dateStr\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"true"},"outputFormat":"${EdgeTtsConstants.OUTPUT_FORMAT}"}}}}""" +
            "\r\n"

        val ssmlMsg =
            "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${dateStr}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml

        client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(speechConfigMsg)
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, messageText: String) {
                    if (messageText.contains("Path:audio.metadata")) {
                        val bodyIdx = messageText.indexOf("\r\n\r\n")
                        val jsonBody = if (bodyIdx >= 0) messageText.substring(bodyIdx + 4) else messageText
                        searchCursor = parseWordBoundaries(jsonBody, text, searchCursor, boundaries)
                    } else if (messageText.contains("Path:turn.end")) {
                        webSocket.close(1000, "Normal Closure")
                        future.complete(SynthesisResult(audioStream.toByteArray(), boundaries.toList()))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size >= 2) {
                        val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        val audioOffset = 2 + headerLength
                        if (audioOffset <= data.size) {
                            val headerText = String(data, 2, headerLength, StandardCharsets.UTF_8)
                            if (headerText.contains("Path:audio")) {
                                val mp3Len = data.size - audioOffset
                                if (mp3Len > 0) {
                                    audioStream.write(data, audioOffset, mp3Len)
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    future.completeExceptionally(t)
                }
            }
        )

        return future
    }

    private val boundaryItemRegex =
        """"Offset"\s*:\s*(\d+)\s*,\s*"Duration"\s*:\s*(\d+)\s*,\s*"text"\s*:\s*\{\s*"Text"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()

    private fun parseWordBoundaries(
        json: String,
        originalText: String,
        startCursor: Int,
        out: MutableList<WordBoundary>
    ): Int {
        var cursor = startCursor
        for (m in boundaryItemRegex.findAll(json)) {
            val offsetTicks = m.groupValues[1].toLong()
            val durationTicks = m.groupValues[2].toLong()
            val word = unescapeJsonString(m.groupValues[3])
            if (word.isNotEmpty()) {
                val idx = originalText.indexOf(word, cursor)
                val actualStart = if (idx >= 0) idx else cursor
                val actualEnd = (actualStart + word.length).coerceAtMost(originalText.length)
                if (idx >= 0) {
                    cursor = actualEnd
                }
                out.add(
                    WordBoundary(
                        offsetMs = offsetTicks / 10000.0,
                        durationMs = durationTicks / 10000.0,
                        text = word,
                        startChar = actualStart,
                        endChar = actualEnd
                    )
                )
            }
        }
        return cursor
    }

    private fun unescapeJsonString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            try {
                                val hex = s.substring(i + 2, i + 6)
                                sb.append(hex.toInt(16).toChar())
                                i += 6
                            } catch (_: Throwable) {
                                sb.append(c)
                                i += 1
                            }
                        } else {
                            sb.append(c)
                            i += 1
                        }
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    private fun escapeXml(str: String): String =
        str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }

        fun javascriptDate(now: Date = Date()): String {
            val formatter = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter.format(now) + " GMT+0000 (Coordinated Universal Time)"
        }
    }
}
