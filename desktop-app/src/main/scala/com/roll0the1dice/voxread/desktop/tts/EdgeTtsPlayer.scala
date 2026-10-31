package com.roll0the1dice.voxread.desktop.tts

import java.io.{File, FileOutputStream}
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicLong
import javafx.application.Platform
import javafx.scene.media.{Media, MediaPlayer}
import scala.util.Using

class EdgeTtsPlayer:

  private val client = new EdgeTtsClient()
  private var currentPlayer: Option[MediaPlayer] = None
  private var currentTempFile: Option[File] = None
  private val sessionCounter = new AtomicLong(0L)
  private val cache = new ConcurrentHashMap[String, CompletableFuture[SynthesisResult]]()

  private def cacheKey(text: String, voice: String, rate: String, pitch: String): String =
    s"$voice|$rate|$pitch|${text.trim}"

  def prefetch(
    text: String,
    voice: String = EdgeTtsConstants.DefaultVoice,
    rate: String = "+0%",
    pitch: String = "+0Hz"
  ): CompletableFuture[SynthesisResult] =
    val trimmed = text.trim
    if (trimmed.isEmpty)
      CompletableFuture.completedFuture(SynthesisResult(Array.emptyByteArray, Seq.empty))
    else
      val key = cacheKey(trimmed, voice, rate, pitch)
      cache.computeIfAbsent(key, _ => {
        val fut = client.synthesizeWithMetadata(trimmed, voice, rate, pitch)
        fut.whenComplete { (_, ex) =>
          if (ex != null) cache.remove(key, fut)
        }
        fut
      })

  def play(
    text: String,
    voice: String = EdgeTtsConstants.DefaultVoice,
    rate: String = "+0%",
    pitch: String = "+0Hz",
    onStart: () => Unit = () => (),
    onWordBoundary: WordBoundary => Unit = _ => (),
    onProgress: Double => Unit = _ => (),
    onFinish: () => Unit = () => (),
    onError: Throwable => Unit = _ => ()
  ): Unit =
    stop()
    val mySession = sessionCounter.incrementAndGet()

    if (cache.size() > 64)
      cache.clear()

    prefetch(text, voice, rate, pitch).thenAccept { result =>
      if (sessionCounter.get() == mySession)
        val mp3Bytes = result.audioBytes
        val boundaries = result.wordBoundaries
        if (mp3Bytes.isEmpty)
          Platform.runLater(() => {
            if (sessionCounter.get() == mySession)
              onError(new RuntimeException("Synthesized audio is empty"))
          })
        else
          val temp = File.createTempFile("voxread_tts_", ".mp3")
          temp.deleteOnExit()
          Using.resource(new FileOutputStream(temp))(_.write(mp3Bytes))

          Platform.runLater { () =>
            if (sessionCounter.get() != mySession)
              try { temp.delete() } catch { case _: Throwable => () }
            else
              currentTempFile = Some(temp)
              try
                val media = new Media(temp.toURI.toString)
                val player = new MediaPlayer(media)
                currentPlayer = Some(player)

                var lastWordIdx = -1

                player.setOnPlaying(() => {
                  if (sessionCounter.get() == mySession) {
                    onStart()
                    if (boundaries.nonEmpty && lastWordIdx < 0) {
                      lastWordIdx = 0
                      onWordBoundary(boundaries.head)
                    }
                  }
                })

                player.currentTimeProperty().addListener((_, _, newTime) => {
                  if (sessionCounter.get() == mySession && newTime != null) {
                    val nowMs = newTime.toMillis
                    val total = player.getTotalDuration
                    if (total != null && total.toMillis > 0) {
                      val pct = Math.max(0.0, Math.min(100.0, (nowMs / total.toMillis) * 100.0))
                      onProgress(pct)
                    }
                    // 查找当前时间对应的 WordBoundary
                    if (boundaries.nonEmpty) {
                      var idx = boundaries.lastIndexWhere(_.offsetMs <= nowMs + 30.0)
                      if (idx < 0) idx = 0
                      if (idx != lastWordIdx) {
                        lastWordIdx = idx
                        onWordBoundary(boundaries(idx))
                      }
                    }
                  }
                })

                player.setOnEndOfMedia { () =>
                  if (sessionCounter.get() == mySession)
                    onProgress(100.0)
                    onFinish()
                    cleanup()
                }
                player.setOnError { () =>
                  if (sessionCounter.get() == mySession)
                    val err = player.getError
                    onError(if (err != null) err else new RuntimeException("Unknown playback error"))
                    cleanup()
                }

                player.play()
              catch
                case t: Throwable =>
                  onError(t)
                  cleanup()
          }
    }.exceptionally { ex =>
      Platform.runLater(() => {
        if (sessionCounter.get() == mySession)
          onError(ex)
      })
      null
    }

  def pause(): Unit =
    currentPlayer.foreach(_.pause())

  def resume(): Unit =
    currentPlayer.foreach(_.play())

  def stop(): Unit =
    sessionCounter.incrementAndGet()
    currentPlayer.foreach { p =>
      try
        p.stop()
        p.dispose()
      catch
        case _: Throwable => ()
    }
    currentPlayer = None
    cleanup()

  def isPlaying: Boolean = currentPlayer.exists(_.getStatus == MediaPlayer.Status.PLAYING)
  def isPaused: Boolean = currentPlayer.exists(_.getStatus == MediaPlayer.Status.PAUSED)

  private def cleanup(): Unit =
    currentTempFile.foreach { f =>
      try { f.delete() } catch { case _: Throwable => () }
    }
    currentTempFile = None
