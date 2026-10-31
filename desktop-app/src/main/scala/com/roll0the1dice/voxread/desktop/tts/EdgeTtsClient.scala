package com.roll0the1dice.voxread.desktop.tts

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.{Date, Locale, TimeZone, UUID}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.collection.mutable.ArrayBuffer
import okhttp3._
import okio.ByteString

case class WordBoundary(
  offsetMs: Double,
  durationMs: Double,
  text: String,
  startChar: Int,
  endChar: Int
)

case class SynthesisResult(
  audioBytes: Array[Byte],
  wordBoundaries: Seq[WordBoundary]
)

class EdgeTtsClient(client: OkHttpClient = EdgeTtsClient.defaultClient):

  def synthesize(
    text: String,
    voice: String = EdgeTtsConstants.DefaultVoice,
    rate: String = "+0%",
    pitch: String = "+0Hz"
  ): CompletableFuture[Array[Byte]] =
    synthesizeWithMetadata(text, voice, rate, pitch).thenApply(_.audioBytes)

  def synthesizeWithMetadata(
    text: String,
    voice: String = EdgeTtsConstants.DefaultVoice,
    rate: String = "+0%",
    pitch: String = "+0Hz"
  ): CompletableFuture[SynthesisResult] =
    val future = new CompletableFuture[SynthesisResult]()
    val audioStream = new ByteArrayOutputStream()
    val boundaries = ArrayBuffer[WordBoundary]()
    var searchCursor = 0

    val connectionId = UUID.randomUUID().toString.replace("-", "")
    val token = EdgeTtsDrm.generateSecMsGec()
    val url = s"${EdgeTtsConstants.WssUrl}&ConnectionId=$connectionId&Sec-MS-GEC=$token&Sec-MS-GEC-Version=${EdgeTtsConstants.SecMsGecVersion}"

    val request = new Request.Builder()
      .url(url)
      .header("Pragma", "no-cache")
      .header("Cache-Control", "no-cache")
      .header("Origin", EdgeTtsConstants.Origin)
      .header("User-Agent", EdgeTtsConstants.UserAgent)
      .header("Accept-Encoding", "gzip, deflate, br")
      .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
      .build()

    val dateStr = EdgeTtsClient.javascriptDate()
    val requestId = UUID.randomUUID().toString.replace("-", "")

    val ssml =
      s"""<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>
         |<voice name='$voice'>
         |<prosody pitch='$pitch' rate='$rate' volume='+0%'>
         |${escapeXml(text)}
         |</prosody>
         |</voice>
         |</speak>""".stripMargin

    val speechConfigMsg =
      s"X-Timestamp:$dateStr\r\n" +
        "Content-Type:application/json; charset=utf-8\r\n" +
        "Path:speech.config\r\n\r\n" +
        s"""{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"true"},"outputFormat":"${EdgeTtsConstants.OutputFormat}"}}}}""" +
        "\r\n"

    val ssmlMsg =
      s"X-RequestId:$requestId\r\n" +
        "Content-Type:application/ssml+xml\r\n" +
        s"X-Timestamp:${dateStr}Z\r\n" +
        "Path:ssml\r\n\r\n" +
        ssml

    client.newWebSocket(
      request,
      new WebSocketListener {
        override def onOpen(webSocket: WebSocket, response: Response): Unit = {
          webSocket.send(speechConfigMsg)
          webSocket.send(ssmlMsg)
        }

        override def onMessage(webSocket: WebSocket, messageText: String): Unit = {
          if (messageText.contains("Path:audio.metadata")) {
            val bodyIdx = messageText.indexOf("\r\n\r\n")
            val jsonBody = if (bodyIdx >= 0) messageText.substring(bodyIdx + 4) else messageText
            searchCursor = parseWordBoundaries(jsonBody, text, searchCursor, boundaries)
          } else if (messageText.contains("Path:turn.end")) {
            webSocket.close(1000, "Normal Closure")
            future.complete(SynthesisResult(audioStream.toByteArray, boundaries.toSeq))
          }
        }

        override def onMessage(webSocket: WebSocket, bytes: ByteString): Unit = {
          val data = bytes.toByteArray
          if (data.length >= 2) {
            val headerLength = ((data(0).toInt & 0xFF) << 8) | (data(1).toInt & 0xFF)
            val audioOffset = 2 + headerLength
            if (audioOffset <= data.length) {
              val headerText = new String(data, 2, headerLength, StandardCharsets.UTF_8)
              if (headerText.contains("Path:audio")) {
                val mp3Len = data.length - audioOffset
                if (mp3Len > 0) {
                  audioStream.write(data, audioOffset, mp3Len)
                }
              }
            }
          }
        }

        override def onFailure(webSocket: WebSocket, t: Throwable, response: Response): Unit = {
          future.completeExceptionally(t)
        }
      }
    )

    future

  private val boundaryItemRegex =
    """"Offset"\s*:\s*(\d+)\s*,\s*"Duration"\s*:\s*(\d+)\s*,\s*"text"\s*:\s*\{\s*"Text"\s*:\s*"((?:[^"\\]|\\.)*)"""".r

  private def parseWordBoundaries(
    json: String,
    originalText: String,
    startCursor: Int,
    out: ArrayBuffer[WordBoundary]
  ): Int =
    var cursor = startCursor
    boundaryItemRegex.findAllMatchIn(json).foreach { m =>
      val offsetTicks = m.group(1).toLong
      val durationTicks = m.group(2).toLong
      val word = unescapeJsonString(m.group(3))
      if (word.nonEmpty)
        val idx = originalText.indexOf(word, cursor)
        val actualStart = if (idx >= 0) idx else cursor
        val actualEnd = Math.min(originalText.length, actualStart + word.length)
        if (idx >= 0)
          cursor = actualEnd
        out += WordBoundary(
          offsetMs = offsetTicks / 10000.0,
          durationMs = durationTicks / 10000.0,
          text = word,
          startChar = actualStart,
          endChar = actualEnd
        )
    }
    cursor

  private def unescapeJsonString(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while (i < s.length)
      val c = s.charAt(i)
      if (c == '\\' && i + 1 < s.length)
        val next = s.charAt(i + 1)
        next match
          case '"'  => sb.append('"'); i += 2
          case '\\' => sb.append('\\'); i += 2
          case '/'  => sb.append('/'); i += 2
          case 'n'  => sb.append('\n'); i += 2
          case 'r'  => sb.append('\r'); i += 2
          case 't'  => sb.append('\t'); i += 2
          case 'u' if i + 5 < s.length =>
            try
              val hex = s.substring(i + 2, i + 6)
              sb.append(Integer.parseInt(hex, 16).toChar)
              i += 6
            catch
              case _: Throwable => sb.append(c); i += 1
          case _ => sb.append(next); i += 2
      else
        sb.append(c)
        i += 1
    sb.toString()

  private def escapeXml(str: String): String =
    str.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

object EdgeTtsClient:
  lazy val defaultClient: OkHttpClient = new OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

  def javascriptDate(now: Date = new Date()): String =
    val formatter = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
    formatter.setTimeZone(TimeZone.getTimeZone("UTC"))
    formatter.format(now) + " GMT+0000 (Coordinated Universal Time)"
