/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import android.util.LruCache
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber

/**
 * Blocking, streamable synthesis session with LRU caching & background preloading.
 * 具备 LRU 内存缓存与后台异步预加载功能。
 */
internal class EdgeTtsSynthesizer(
    private val httpClient: OkHttpClient = sharedHttpClient,
) {

    @Volatile
    private var activeClient: EdgeTtsClient? = null

    // 长连接保活池
    private val poolLock = Any()
    private var pooledClient: EdgeTtsClient? = null
    private var lastUsedTime: Long = 0L

    // 预加载协程作用域（后台低优先级）
    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun cancel() {
        activeClient?.cancel()
        discardPooledClient()
    }

    private fun discardPooledClient() {
        synchronized(poolLock) {
            pooledClient?.close()
            pooledClient = null
        }
    }

    data class Request(
        val text: String,
        val voice: String,
        val rate: Double,
        val pitch: Double,
    )

    interface Listener {
        fun onPcm(pcm: ByteArray): Boolean
        fun onRange(start: Int, end: Int, frame: Int)
        fun onDone()
        fun onError(network: Boolean, message: String)
    }

    // 缓存数据模型：存储 PCM 数据切片与同步文字高亮边界
    internal data class RangeRecord(val start: Int, val end: Int, val frame: Int)
    internal class CachedAudio(
        val pcmChunks: List<ByteArray>,
        val ranges: List<RangeRecord>,
    ) {
        val sizeInBytes: Int = pcmChunks.sumOf { it.size }
    }

    /**
     * 🌟 预加载接口：在后台静默把下一句文本合成并装入 LRU 缓存中
     */
    fun preload(request: Request) {
        val key = cacheKey(request)
        if (audioCache.get(key) != null) {
            return // 已经缓存过了，直接跳过
        }
        preloadScope.launch {
            try {
                val cancelled = AtomicBoolean(false)
                val dummyListener = object : Listener {
                    override fun onPcm(pcm: ByteArray): Boolean = !cancelled.get()
                    override fun onRange(start: Int, end: Int, frame: Int) {}
                    override fun onDone() {}
                    override fun onError(network: Boolean, message: String) {}
                }
                synthesize(request, cancelled, dummyListener, isPreload = true)
                Timber.d("Edge TTS: 预加载成功装入 LRU -> text='${request.text.take(20)}...'")
            } catch (e: Throwable) {
                Timber.w(e, "Edge TTS: 预加载失败")
            }
        }
    }

    fun synthesize(
        request: Request,
        cancelled: AtomicBoolean,
        listener: Listener,
        isPreload: Boolean = false,
    ) {
        val key = cacheKey(request)

        // 🌟 1. 优先检查 LRU 缓存：如果命中，0 毫秒极速播放！
        val cached = audioCache.get(key)
        if (cached != null) {
            if (!isPreload) {
                Timber.i("Edge TTS: 🎯 命中 LRU 缓存直接发声 (0ms网络延迟) -> '${request.text.take(20)}...'")
                for (range in cached.ranges) {
                    listener.onRange(range.start, range.end, range.frame)
                }
                for (pcm in cached.pcmChunks) {
                    if (cancelled.get() || !listener.onPcm(pcm)) {
                        return
                    }
                }
                listener.onDone()
            }
            return
        }

        // 2. 未命中缓存：走正常的网络流式合成
        val chunks = EdgeTtsSsml.chunkText(request.text)
        if (chunks.isEmpty() || chunks.all { it.isBlank() }) {
            listener.onDone()
            return
        }

        // 录制容器：一边播放给用户听，一边收集完整的音频和断句点放入 LRU
        val recordedPcm = mutableListOf<ByteArray>()
        val recordedRanges = mutableListOf<RangeRecord>()

        val recordingListener = object : Listener {
            override fun onPcm(pcm: ByteArray): Boolean {
                recordedPcm.add(pcm.clone())
                return listener.onPcm(pcm)
            }

            override fun onRange(start: Int, end: Int, frame: Int) {
                recordedRanges.add(RangeRecord(start, end, frame))
                listener.onRange(start, end, frame)
            }

            override fun onDone() {
                listener.onDone()
            }

            override fun onError(network: Boolean, message: String) {
                listener.onError(network, message)
            }
        }

        for ((index, chunk) in chunks.withIndex()) {
            if (cancelled.get()) {
                return
            }
            val ssml = EdgeTtsSsml.wrapEscaped(chunk, request.voice, request.rate, request.pitch)
            var outcome = runTurn(ssml, request.text, cancelled, recordingListener, forceFresh = false)

            if (outcome is TurnOutcome.Failure && !outcome.forbidden && !cancelled.get()) {
                outcome = runTurn(ssml, request.text, cancelled, recordingListener, forceFresh = true)
            }

            when (outcome) {
                TurnOutcome.Success -> Unit
                TurnOutcome.Cancelled -> return
                is TurnOutcome.Failure -> {
                    if (outcome.forbidden) {
                        EdgeTtsDrm.adjustClockFromHttpDate(outcome.httpDate)
                        val retry = runTurn(ssml, request.text, cancelled, recordingListener, forceFresh = true)
                        if (retry is TurnOutcome.Failure) {
                            recordingListener.onError(true, retry.message)
                            return
                        }
                        if (retry == TurnOutcome.Cancelled) {
                            return
                        }
                    } else {
                        recordingListener.onError(outcome.network, outcome.message)
                        return
                    }
                }
            }

            if (index == chunks.lastIndex) {
                // 🌟 3. 本段合成完全成功：将收集好的数据整块推入 LRU 缓存
                if (recordedPcm.isNotEmpty() && !cancelled.get()) {
                    val entry = CachedAudio(recordedPcm, recordedRanges)
                    audioCache.put(key, entry)
                }
                recordingListener.onDone()
            }
        }
    }

    private fun obtainClient(forceFresh: Boolean): Pair<EdgeTtsClient, Boolean> {
        synchronized(poolLock) {
            val existing = pooledClient
            val now = System.currentTimeMillis()
            return if (!forceFresh && existing != null && (now - lastUsedTime < POOL_IDLE_TIMEOUT_MS)) {
                pooledClient = null
                Pair(existing, true)
            } else {
                existing?.close()
                pooledClient = null
                Pair(EdgeTtsClient(httpClient), false)
            }
        }
    }

    private fun runTurn(
        ssml: String,
        originalText: String,
        cancelled: AtomicBoolean,
        listener: Listener,
        forceFresh: Boolean = false,
    ): TurnOutcome {
        val (client, isReused) = obtainClient(forceFresh)
        val decoder = Mp3StreamDecoder()
        var searchFrom = 0
        activeClient = client
        var outcome: TurnOutcome = TurnOutcome.Cancelled

        try {
            decoder.start()
            var opened = isReused

            if (!isReused) {
                client.connect()
            } else {
                try {
                    client.sendSsml(ssml)
                } catch (e: Exception) {
                    return TurnOutcome.Failure(
                        network = true,
                        forbidden = false,
                        message = "Reused write failed: ${e.message}",
                        httpDate = null
                    )
                }
            }

            var lastEventAt = System.currentTimeMillis()
            while (!cancelled.get()) {
                val event = client.poll(100L)
                if (event == null) {
                    if (System.currentTimeMillis() - lastEventAt > TURN_TIMEOUT_MS) {
                        outcome = TurnOutcome.Failure(
                            network = true,
                            forbidden = false,
                            message = "Timed out waiting for Edge TTS audio",
                            httpDate = null
                        )
                        return outcome
                    }
                    continue
                }
                lastEventAt = System.currentTimeMillis()
                when (event) {
                    EdgeTtsClient.Event.Opened -> {
                        opened = true
                        client.sendSpeechConfig()
                        client.sendSsml(ssml)
                    }
                    is EdgeTtsClient.Event.Mp3 -> {
                        var stopped = false
                        decoder.decode(event.bytes) { pcm ->
                            if (!listener.onPcm(pcm)) {
                                stopped = true
                            }
                        }
                        if (stopped) {
                            outcome = TurnOutcome.Cancelled
                            return outcome
                        }
                    }
                    is EdgeTtsClient.Event.Metadata -> {
                        searchFrom = dispatchRanges(event.json, originalText, searchFrom, listener)
                    }
                    EdgeTtsClient.Event.TurnEnd -> {
                        var stopped = false
                        decoder.finish { pcm ->
                            if (!listener.onPcm(pcm)) {
                                stopped = true
                            }
                        }
                        outcome = if (stopped) TurnOutcome.Cancelled else TurnOutcome.Success
                        return outcome
                    }
                    is EdgeTtsClient.Event.Failed -> {
                        outcome = TurnOutcome.Failure(
                            network = true,
                            forbidden = event.forbidden,
                            message = event.message,
                            httpDate = event.httpDate
                        )
                        return outcome
                    }
                    EdgeTtsClient.Event.Closed -> {
                        outcome = TurnOutcome.Failure(
                            network = true,
                            forbidden = false,
                            message = if (opened) "WebSocket closed before turn.end" else "WebSocket closed",
                            httpDate = null
                        )
                        return outcome
                    }
                }
            }
            outcome = TurnOutcome.Cancelled
            return outcome
        } catch (e: Exception) {
            Timber.e(e, "Edge TTS synthesis failed")
            outcome = TurnOutcome.Failure(
                network = true,
                forbidden = false,
                message = e.message ?: "Synthesis failed",
                httpDate = null
            )
            return outcome
        } finally {
            if (activeClient === client) {
                activeClient = null
            }
            decoder.close()
            if (outcome is TurnOutcome.Success && !cancelled.get()) {
                synchronized(poolLock) {
                    lastUsedTime = System.currentTimeMillis()
                    pooledClient = client
                }
            } else {
                client.close()
            }
        }
    }

    private fun dispatchRanges(
        json: String,
        originalText: String,
        searchFrom: Int,
        listener: Listener,
    ): Int {
        return try {
            val metadata = JSONObject(json).optJSONArray("Metadata") ?: return searchFrom
            var cursor = searchFrom
            for (i in 0 until metadata.length()) {
                val item = metadata.optJSONObject(i) ?: continue
                val type = item.optString("Type")
                if (type != "WordBoundary" && type != "SentenceBoundary") {
                    continue
                }
                val data = item.optJSONObject("Data") ?: continue
                val word = data.optJSONObject("text")
                    ?.optString("Text")
                    .orEmpty()
                if (word.isEmpty()) {
                    continue
                }
                val start = originalText.indexOf(word, cursor)
                if (start < 0) {
                    continue
                }
                val end = start + word.length
                val offsetTicks = data.optLong("Offset")
                val frame = (offsetTicks * EdgeTtsConstants.SAMPLE_RATE_HZ / 10_000_000L).toInt()
                listener.onRange(start, end, frame)
                cursor = end
            }
            cursor
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse Edge TTS metadata")
            searchFrom
        }
    }

    private fun cacheKey(request: Request): String =
        "${request.voice}:${request.rate}:${request.pitch}:${request.text.trim()}"

    private sealed class TurnOutcome {
        data object Success : TurnOutcome()
        data object Cancelled : TurnOutcome()
        data class Failure(
            val network: Boolean,
            val forbidden: Boolean,
            val message: String,
            val httpDate: String?,
        ) : TurnOutcome()
    }

    companion object {
        private const val TURN_TIMEOUT_MS: Long = 60_000L
        private const val POOL_IDLE_TIMEOUT_MS: Long = 15_000L

        // 🌟 分配 20MB 内存给 LRU 音频缓存（足以容纳 80~100 个长句）
        private const val MAX_CACHE_SIZE_BYTES = 20 * 1024 * 1024

        internal val audioCache = object : LruCache<String, CachedAudio>(MAX_CACHE_SIZE_BYTES) {
            override fun sizeOf(key: String, value: CachedAudio): Int = value.sizeInBytes
        }

        internal val sharedHttpClient: OkHttpClient =
            EdgeTtsClient.defaultHttpClient()
    }
}