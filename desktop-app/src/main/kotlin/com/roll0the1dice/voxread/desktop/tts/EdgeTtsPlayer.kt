package com.roll0the1dice.voxread.desktop.tts

import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class EdgeTtsPlayer {

    init {
        ensureToolkitInitialized()
    }

    private val client = EdgeTtsClient()
    private var currentPlayer: MediaPlayer? = null
    private var currentTempFile: File? = null
    private var currentWatchdog: PauseTransition? = null
    private val sessionCounter = AtomicLong(0L)
    private val cache = ConcurrentHashMap<String, CompletableFuture<SynthesisResult>>()

    @Volatile
    var gainFactor: Double = EdgeTtsConstants.DEFAULT_GAIN_FACTOR
        private set

    @Volatile
    var drcEnabled: Boolean = EdgeTtsConstants.DEFAULT_DRC_ENABLED
        private set

    private fun cacheKey(text: String, voice: String, rate: String, pitch: String, volume: String): String =
        "$voice|$rate|$pitch|$volume|${text.trim()}"

    fun updatePlaybackGain(
        newGainFactor: Double,
        newDrcEnabled: Boolean = drcEnabled
    ) {
        gainFactor = newGainFactor.coerceIn(0.1, 3.0)
        drcEnabled = newDrcEnabled
        Platform.runLater {
            currentPlayer?.let { player ->
                applyGainToMediaPlayer(player, gainFactor, drcEnabled)
            }
        }
    }

    private fun applyGainToMediaPlayer(
        player: MediaPlayer,
        targetGain: Double,
        enableDrc: Boolean
    ) {
        try {
            player.volume = targetGain.coerceIn(0.0, 1.0)
            val eq = player.audioEqualizer
            if (eq != null) {
                val bands = eq.bands
                val total = bands.size
                if (targetGain > 1.0 || enableDrc) {
                    eq.isEnabled = true
                    for (i in 0 until total) {
                        bands[i].gain = TtsAudioProcessor.equalizerBandGainDb(targetGain, enableDrc, i, total)
                    }
                } else {
                    for (i in 0 until total) {
                        bands[i].gain = 0.0
                    }
                    eq.isEnabled = false
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun prefetch(
        text: String,
        voice: String = EdgeTtsConstants.DEFAULT_VOICE,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = TtsAudioProcessor.gainToSsmlVolume(gainFactor)
    ): CompletableFuture<SynthesisResult> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(SynthesisResult(ByteArray(0), emptyList()))
        }
        val key = cacheKey(trimmed, voice, rate, pitch, volume)
        return cache.computeIfAbsent(key) {
            val fut = client.synthesizeWithMetadata(trimmed, voice, rate, pitch, volume)
            fut.whenComplete { _, ex ->
                if (ex != null) cache.remove(key, fut)
            }
            fut
        }
    }

    fun play(
        text: String,
        voice: String = EdgeTtsConstants.DEFAULT_VOICE,
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = TtsAudioProcessor.gainToSsmlVolume(gainFactor),
        onStart: () -> Unit = {},
        onWordBoundary: (WordBoundary) -> Unit = {},
        onProgress: (Double) -> Unit = {},
        onFinish: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        stop()
        val mySession = sessionCounter.incrementAndGet()

        if (cache.size > 64) {
            cache.clear()
        }

        prefetch(text, voice, rate, pitch, volume).thenAccept { result ->
            if (sessionCounter.get() == mySession) {
                val rawMp3Bytes = result.audioBytes
                val boundaries = result.wordBoundaries
                if (rawMp3Bytes.isEmpty()) {
                    Platform.runLater {
                        if (sessionCounter.get() == mySession) {
                            onStart()
                            onProgress(100.0)
                            onFinish()
                        }
                    }
                } else {
                    val processedMp3Bytes = TtsAudioProcessor.processMp3WithDrcAndGain(
                        mp3Bytes = rawMp3Bytes,
                        gainFactor = gainFactor,
                        drcEnabled = drcEnabled
                    )
                    val temp = File.createTempFile("voxread_tts_", ".mp3")
                    temp.deleteOnExit()
                    FileOutputStream(temp).use { it.write(processedMp3Bytes) }

                    Platform.runLater {
                        if (sessionCounter.get() != mySession) {
                            try { temp.delete() } catch (_: Throwable) {}
                        } else {
                            currentTempFile = temp
                            val finished = AtomicBoolean(false)

                            fun completeSession() {
                                if (sessionCounter.get() == mySession && finished.compareAndSet(false, true)) {
                                    stopWatchdog()
                                    onProgress(100.0)
                                    onFinish()
                                    cleanup()
                                }
                            }

                            val boundaryEndMs = boundaries.lastOrNull()?.let { it.offsetMs + it.durationMs } ?: 0.0
                            val estimatedTotalMs = max(boundaryEndMs, processedMp3Bytes.size / 6.0)
                            val initialTimeoutMs = max(1200.0, estimatedTotalMs + 800.0)
                            val watchdog = PauseTransition(Duration.millis(initialTimeoutMs))
                            watchdog.setOnFinished { completeSession() }
                            currentWatchdog = watchdog

                            try {
                                val media = Media(temp.toURI().toString())
                                val player = MediaPlayer(media)
                                applyGainToMediaPlayer(player, gainFactor, drcEnabled)
                                currentPlayer = player

                                var lastWordIdx = -1

                                player.setOnReady {
                                    if (sessionCounter.get() == mySession && !finished.get()) {
                                        applyGainToMediaPlayer(player, gainFactor, drcEnabled)
                                    }
                                }

                                player.setOnPlaying {
                                    if (sessionCounter.get() == mySession && !finished.get()) {
                                        applyGainToMediaPlayer(player, gainFactor, drcEnabled)
                                        onStart()
                                        if (boundaries.isNotEmpty() && lastWordIdx < 0) {
                                            lastWordIdx = 0
                                            onWordBoundary(boundaries[0])
                                        }
                                        watchdog.playFromStart()
                                    }
                                }

                                player.currentTimeProperty().addListener { _, _, newTime ->
                                    if (sessionCounter.get() == mySession && !finished.get() && newTime != null) {
                                        val nowMs = newTime.toMillis()
                                        val total = player.totalDuration
                                        val totalMs = if (total != null && !total.isUnknown && !total.isIndefinite && total.toMillis() > 0) {
                                            total.toMillis()
                                        } else {
                                            estimatedTotalMs
                                        }

                                        if (totalMs > 0) {
                                            val pct = max(0.0, min(100.0, (nowMs / totalMs) * 100.0))
                                            onProgress(pct)
                                            val remainingMs = max(0.0, totalMs - nowMs)
                                            watchdog.stop()
                                            watchdog.duration = Duration.millis(max(600.0, remainingMs + 600.0))
                                            if (player.status == MediaPlayer.Status.PLAYING) {
                                                watchdog.playFromStart()
                                            }
                                        }
                                        if (boundaries.isNotEmpty()) {
                                            var idx = boundaries.indexOfLast { it.offsetMs <= nowMs + 30.0 }
                                            if (idx < 0) idx = 0
                                            if (idx != lastWordIdx) {
                                                lastWordIdx = idx
                                                onWordBoundary(boundaries[idx])
                                            }
                                        }
                                    }
                                }

                                player.setOnEndOfMedia {
                                    completeSession()
                                }
                                player.setOnError {
                                    if (sessionCounter.get() == mySession && !finished.get()) {
                                        completeSession()
                                    }
                                }

                                watchdog.playFromStart()
                                player.play()
                            } catch (t: Throwable) {
                                stopWatchdog()
                                onError(t)
                                cleanup()
                            }
                        }
                    }
                }
            }
        }.exceptionally { ex ->
            Platform.runLater {
                if (sessionCounter.get() == mySession) {
                    onError(ex)
                }
            }
            null
        }
    }

    fun pause() {
        currentWatchdog?.pause()
        currentPlayer?.pause()
    }

    fun resume() {
        currentPlayer?.play()
        currentWatchdog?.play()
    }

    fun stop() {
        sessionCounter.incrementAndGet()
        stopWatchdog()
        currentPlayer?.let { p ->
            try {
                p.stop()
                p.dispose()
            } catch (_: Throwable) {}
        }
        currentPlayer = null
        cleanup()
    }

    val isPlaying: Boolean get() = currentPlayer?.status == MediaPlayer.Status.PLAYING
    val isPaused: Boolean get() = currentPlayer?.status == MediaPlayer.Status.PAUSED

    private fun stopWatchdog() {
        currentWatchdog?.let { w ->
            try { w.stop() } catch (_: Throwable) {}
        }
        currentWatchdog = null
    }

    private fun cleanup() {
        currentTempFile?.let { f ->
            try { f.delete() } catch (_: Throwable) {}
        }
        currentTempFile = null
    }

    companion object {
        private val toolkitInitialized = AtomicBoolean(false)

        fun ensureToolkitInitialized() {
            if (toolkitInitialized.compareAndSet(false, true)) {
                try {
                    Platform.startup {
                        Platform.setImplicitExit(false)
                    }
                } catch (_: IllegalStateException) {
                    try {
                        Platform.setImplicitExit(false)
                    } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
        }
    }
}
