package com.roll0the1dice.voxread.desktop.ui.reader

import java.io.File
import javafx.concurrent.Worker
import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control._
import scalafx.scene.effect.DropShadow
import scalafx.scene.layout._
import scalafx.scene.paint.Color
import scalafx.scene.web.WebView
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.tts.{EdgeTtsConstants, EdgeTtsPlayer}
import com.roll0the1dice.voxread.desktop.util.EpubContentReader

/**
 * 阅读器视图，对齐 Android 原版 fragment_reader.xml、VisualReaderFragment.kt 与 ReaderToolbar.kt:
 * - 沉浸式阅读正文区 (WebView) + 实时句子与词语级 TTS 同步高亮及自动平滑滚动
 * - 顶部浮动章节标题胶囊 (ReaderChapterTitle) + 返回按钮
 * - 右下角悬浮耳机朗读按钮 (ReaderListenButton, 56dp 圆形按钮)
 * - 右下角浮动页码指示器 (ReaderPageIndicator，自适应紧凑宽度，绝不遮挡底栏)
 * - 独立底部工具栏 (ReaderBottomToolbar: 目录、上一章、字号缩放、主题切换、下一章)
 * - 侧边目录抽屉 (Outline Drawer)
 * - 完整的 AI 阅读播放器浮层 (TtsPlayerOverlay)
 */
class ReaderView(
  val book: Book,
  onClose: () => Unit
) extends StackPane:

  val ToolbarInk = "#60656E"
  val PlayerBlue = "#00A5F5"

  private val contentReader = new EpubContentReader(new File(book.href))
  private val ttsPlayer = new EdgeTtsPlayer()

  private var currentChapterIndex = 0
  private var currentFontSize = 18
  private var currentReadingTheme = "light" // "light", "sepia", "dark"
  private var isTtsPlaying = false

  // TTS 句子队列与高亮配置
  private var chapterSentences: Vector[String] = Vector.empty
  private var currentSentenceIndex = 0
  private var currentTtsVoice = EdgeTtsConstants.DefaultVoice
  private var currentTtsRate = "+0%"
  private var currentSentenceHighlightRgba = "rgba(255, 220, 40, 0.45)"
  private var currentWordHighlightRgba = "rgba(255, 180, 0, 0.80)"
  private var autoPlayOnChapterLoad = false

  // 1. 核心阅读引擎 WebView
  private val webView = new WebView()
  private val webEngine = webView.engine

  webEngine.getLoadWorker.stateProperty().addListener((_, _, newState) => {
    if (newState == Worker.State.SUCCEEDED) {
      prepareChapterSentencesFromDom()
      applyHighlightColorsToDom()
      if (autoPlayOnChapterLoad) {
        autoPlayOnChapterLoad = false
        currentSentenceIndex = 0
        playCurrentSentence()
      } else if (isTtsPlaying || ttsPlayer.isPaused) {
        highlightSentenceInWebView(currentSentenceIndex, -1, -1)
      }
    }
  })

  // 2. 顶部浮动栏: 返回按钮 + ReaderChapterTitle (白底 85% 透明度，4dp 圆角，紧凑包裹)
  private val btnBack = new Button:
    prefWidth = 36
    prefHeight = 36
    minWidth = 36
    minHeight = 36
    maxWidth = 36
    maxHeight = 36
    val icon = new FontIcon(Feather.CHEVRON_LEFT)
    icon.setIconSize(20)
    icon.setIconColor(javafx.scene.paint.Color.web(ToolbarInk))
    icon.setMouseTransparent(true)
    graphic = icon
    style =
      "-fx-background-color: rgba(255, 255, 255, 0.92); " +
      "-fx-background-radius: 18px; " +
      "-fx-border-radius: 18px; " +
      "-fx-padding: 0; " +
      "-fx-alignment: center; " +
      "-fx-cursor: hand;"
    effect = new DropShadow:
      radius = 4.0
      offsetY = 1.0
      color = Color.rgb(0, 0, 0, 0.15)
    tooltip = new Tooltip("返回书架")
    onAction = _ => {
      stopTtsCompletely()
      onClose()
    }

  private val lblChapterTitle = new Label(""):
    style = s"-fx-font-size: 12px; -fx-text-fill: $ToolbarInk; -fx-font-weight: 500;"
    maxWidth = 400
    mouseTransparent = true

  private val chapterTitlePill = new HBox(lblChapterTitle):
    alignment = Pos.CenterLeft
    padding = Insets(5, 10, 5, 10)
    maxWidth = Region.USE_PREF_SIZE
    maxHeight = Region.USE_PREF_SIZE
    mouseTransparent = true
    style =
      "-fx-background-color: rgba(255, 255, 255, 0.88); " +
      "-fx-background-radius: 4px; " +
      "-fx-border-radius: 4px;"
    effect = new DropShadow:
      radius = 3.0
      offsetY = 1.0
      color = Color.rgb(0, 0, 0, 0.12)

  private val topOverlayBar = new HBox(10):
    alignment = Pos.CenterLeft
    fillHeight = false
    maxWidth = Region.USE_PREF_SIZE
    maxHeight = Region.USE_PREF_SIZE
    pickOnBounds = false
    children = Seq(btnBack, chapterTitlePill)

  StackPane.setAlignment(topOverlayBar, Pos.TopLeft)
  StackPane.setMargin(topOverlayBar, Insets(14, 0, 0, 18))

  // 3. 右下角 ReaderPageIndicator (白底 85% 透明度，4dp 圆角，紧凑包裹且鼠标穿透)
  private val lblPageIndicator = new Label("第 1 / 1 章"):
    style = s"-fx-font-size: 11px; -fx-text-fill: $ToolbarInk;"
    mouseTransparent = true

  private val pageIndicatorPill = new HBox(lblPageIndicator):
    alignment = Pos.Center
    padding = Insets(4, 10, 4, 10)
    maxWidth = Region.USE_PREF_SIZE
    maxHeight = Region.USE_PREF_SIZE
    mouseTransparent = true
    style =
      "-fx-background-color: rgba(255, 255, 255, 0.88); " +
      "-fx-background-radius: 4px; " +
      "-fx-border-radius: 4px;"
    effect = new DropShadow:
      radius = 3.0
      offsetY = 1.0
      color = Color.rgb(0, 0, 0, 0.12)

  // 4. 右下角悬浮耳机朗读按钮 (ReaderListenButton: 56dp 圆形悬浮按钮)
  private val listenIcon = new FontIcon(Feather.HEADPHONES)
  listenIcon.setIconSize(24)
  listenIcon.setIconColor(javafx.scene.paint.Color.WHITE)
  listenIcon.setMouseTransparent(true)

  private val btnListenFab = new Button:
    prefWidth = 56
    prefHeight = 56
    minWidth = 56
    minHeight = 56
    maxWidth = 56
    maxHeight = 56
    graphic = listenIcon
    style =
      s"-fx-background-color: $ToolbarInk; " +
      "-fx-background-radius: 28px; " +
      "-fx-border-radius: 28px; " +
      "-fx-padding: 0; " +
      "-fx-alignment: center; " +
      "-fx-cursor: hand;"
    effect = new DropShadow:
      radius = 8.0
      offsetY = 3.0
      color = Color.rgb(0, 0, 0, 0.25)
    tooltip = new Tooltip("AI 语音朗读")
    onAction = _ => {
      openTtsPlayer()
      // 对齐 Android VisualReaderFragment.kt: 首次点击听书悬浮按钮时自动启动朗读与同步高亮
      if (!isTtsPlaying && !ttsPlayer.isPaused)
        startTtsPlayback()
    }

  // 对齐 VisualReaderFragment.kt: 页码胶囊与悬浮朗读按钮位于阅读区右下角（绝不覆盖底部工具栏）
  private val fabAndPageBox = new VBox(10):
    alignment = Pos.BottomRight
    fillWidth = false
    maxWidth = Region.USE_PREF_SIZE
    maxHeight = Region.USE_PREF_SIZE
    pickOnBounds = false
    children = Seq(pageIndicatorPill, btnListenFab)

  StackPane.setAlignment(fabAndPageBox, Pos.BottomRight)
  StackPane.setMargin(fabAndPageBox, Insets(0, 22, 16, 0))

  // 5. 底部工具栏 (ReaderBottomToolbar: 独立置于 BorderPane.bottom，无任何浮层遮挡)
  private val btnOutline = createToolbarButton(Feather.LIST, "目录", () => toggleOutlineDrawer())
  private val btnPrev = createToolbarButton(Feather.CHEVRON_LEFT, "上一章", () => prevChapter())
  private val btnFontDec = createToolbarButton(Feather.MINUS, "缩小字体", () => adjustFontSize(-2))
  private val btnFontInc = createToolbarButton(Feather.PLUS, "放大字体", () => adjustFontSize(2))
  private val btnTheme = createToolbarButton(Feather.SUN, "切换主题", () => cycleReadingTheme())
  private val btnNext = createToolbarButton(Feather.CHEVRON_RIGHT, "下一章", () => nextChapter())

  private val bottomToolbar = new HBox:
    prefHeight = 52
    minHeight = 52
    maxHeight = 52
    alignment = Pos.Center
    padding = Insets(0, 24, 0, 24)
    style =
      "-fx-background-color: #FFFFFF; " +
      "-fx-border-color: #E2E8F0; " +
      "-fx-border-width: 1 0 0 0;"
    children = Seq(
      btnOutline,
      createExpandingSpacer(),
      btnPrev,
      createExpandingSpacer(),
      btnFontDec,
      createExpandingSpacer(),
      btnFontInc,
      createExpandingSpacer(),
      btnTheme,
      createExpandingSpacer(),
      btnNext
    )

  // 6. 侧边目录抽屉 (Outline Drawer)
  private val outlineList = new ListView[String]():
    items = scalafx.collections.ObservableBuffer(contentReader.chapters.map(_.title)*)
    style = "-fx-background-color: transparent;"
    prefWidth = 260

  outlineList.selectionModel().selectedIndex.onChange { (_, _, newVal) =>
    val idx = newVal.intValue()
    if idx >= 0 && idx < contentReader.chapters.size then
      val wasPlaying = isTtsPlaying
      stopTtsCompletely()
      autoPlayOnChapterLoad = wasPlaying
      loadChapter(idx)
      outlineDrawer.visible = false
  }

  private val outlineHeader = new HBox:
    alignment = Pos.CenterLeft
    padding = Insets(12, 16, 12, 16)
    style = "-fx-border-color: #E2E8F0; -fx-border-width: 0 0 1 0;"
    val titleLbl = new Label("图书目录"):
      style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #252A32;"
    val hSpacer = new Region()
    HBox.setHgrow(hSpacer, Priority.Always)
    val btnCloseOutline = new Button:
      prefWidth = 32
      prefHeight = 32
      minWidth = 32
      minHeight = 32
      val fIcon = new FontIcon(Feather.X)
      fIcon.setIconSize(18)
      fIcon.setIconColor(javafx.scene.paint.Color.web(ToolbarInk))
      fIcon.setMouseTransparent(true)
      graphic = fIcon
      style = "-fx-background-color: transparent; -fx-padding: 0; -fx-alignment: center; -fx-cursor: hand;"
      onAction = _ => { outlineDrawer.visible = false }
    children = Seq(titleLbl, hSpacer, btnCloseOutline)

  private lazy val outlineDrawer: VBox = new VBox:
    prefWidth = 280
    maxWidth = 280
    style =
      "-fx-background-color: #FFFFFF; " +
      "-fx-border-color: #E2E8F0; " +
      "-fx-border-width: 0 1 0 0;"
    effect = new DropShadow:
      radius = 12.0
      offsetX = 4.0
      color = Color.rgb(0, 0, 0, 0.15)
    children = Seq(outlineHeader, outlineList)
    visible = false

  VBox.setVgrow(outlineList, Priority.Always)
  StackPane.setAlignment(outlineDrawer, Pos.CenterLeft)

  // 7. 阅读区容器（正文 WebView + 顶部胶囊 + 右下角悬浮按钮，全部位于 bottomToolbar 上方）
  private val readingAreaStack = new StackPane:
    children = Seq(webView, topOverlayBar, fabAndPageBox, outlineDrawer)

  private val readerLayout = new BorderPane:
    center = readingAreaStack
    bottom = bottomToolbar

  // 8. TtsPlayerOverlay 实例 (初始隐藏，常驻于顶层 StackPane，彻底避免重复 add 导致异常)
  private lazy val ttsOverlay: TtsPlayerOverlay =
    val overlay = new TtsPlayerOverlay(
      book = book,
      chapterTitle = currentChapterTitle,
      onDismiss = () => closeTtsPlayer(),
      onTogglePlay = () => handleTtsPlayPause(),
      onPrev = () => prevTtsSentence(),
      onNext = () => nextTtsSentence(),
      onStop = () => {
        stopTtsCompletely()
        closeTtsPlayer()
      },
      onOutline = () => {
        closeTtsPlayer()
        outlineDrawer.visible = true
      },
      onVoiceChanged = newVoice => {
        currentTtsVoice = newVoice
        if (isTtsPlaying || ttsPlayer.isPaused) {
          playCurrentSentence()
        }
      },
      onSpeedChanged = speed => {
        val pct = Math.round((speed - 1.0) * 100).toInt
        currentTtsRate = if (pct >= 0) s"+$pct%" else s"$pct%"
        if (isTtsPlaying) {
          playCurrentSentence()
        }
      },
      onHighlightColorChanged = (sentRgba, wordRgba) => {
        currentSentenceHighlightRgba = sentRgba
        currentWordHighlightRgba = wordRgba
        applyHighlightColorsToDom()
      }
    )
    overlay.visible = false
    overlay

  children = Seq(readerLayout, ttsOverlay)

  private def currentChapterTitle: String =
    contentReader.chapters.lift(currentChapterIndex).map(_.title).getOrElse(book.displayName)

  // 打开/收起 TTS 播放器
  private def openTtsPlayer(): Unit =
    ttsOverlay.updateChapter(currentChapterTitle)
    ttsOverlay.visible = true
    ttsOverlay.toFront()

  private def closeTtsPlayer(): Unit =
    ttsOverlay.visible = false

  private def toggleOutlineDrawer(): Unit =
    outlineDrawer.visible = !outlineDrawer.visible.value

  private def updateListenButtonState(): Unit =
    val bgColor = if isTtsPlaying then PlayerBlue else ToolbarInk
    btnListenFab.style =
      s"-fx-background-color: $bgColor; " +
      "-fx-background-radius: 28px; " +
      "-fx-border-radius: 28px; " +
      "-fx-padding: 0; " +
      "-fx-alignment: center; " +
      "-fx-cursor: hand;"
    if isTtsPlaying then
      listenIcon.setIconCode(Feather.VOLUME_2)
    else
      listenIcon.setIconCode(Feather.HEADPHONES)

  private def stopTtsCompletely(): Unit =
    autoPlayOnChapterLoad = false
    ttsPlayer.stop()
    isTtsPlaying = false
    updateListenButtonState()
    ttsOverlay.setPlaying(false)
    ttsOverlay.updateProgress(0.0)
    ttsOverlay.updateSpokenText("")
    clearWebViewHighlight()

  // TTS 播放与控制
  private def handleTtsPlayPause(): Unit =
    if ttsPlayer.isPlaying then
      ttsPlayer.pause()
      isTtsPlaying = false
      updateListenButtonState()
      ttsOverlay.setPlaying(false)
    else if ttsPlayer.isPaused then
      ttsPlayer.resume()
      isTtsPlaying = true
      updateListenButtonState()
      ttsOverlay.setPlaying(true)
    else if isTtsPlaying then
      // 正在合成中点击暂停 -> 取消当前合成
      ttsPlayer.stop()
      isTtsPlaying = false
      updateListenButtonState()
      ttsOverlay.setPlaying(false)
    else
      startTtsPlayback()

  private def prevTtsSentence(): Unit =
    if chapterSentences.isEmpty then prepareChapterSentencesFromDom()
    if currentSentenceIndex > 0 then
      currentSentenceIndex -= 1
      playCurrentSentence()
    else if currentChapterIndex > 0 then
      autoPlayOnChapterLoad = true
      loadChapter(currentChapterIndex - 1)

  private def nextTtsSentence(): Unit =
    if chapterSentences.isEmpty then prepareChapterSentencesFromDom()
    if currentSentenceIndex + 1 < chapterSentences.size then
      currentSentenceIndex += 1
      playCurrentSentence()
    else if currentChapterIndex + 1 < contentReader.chapters.size then
      autoPlayOnChapterLoad = true
      loadChapter(currentChapterIndex + 1)

  private def startTtsPlayback(): Unit =
    if chapterSentences.isEmpty then
      prepareChapterSentencesFromDom()
    if chapterSentences.isEmpty then return

    if currentSentenceIndex < 0 || currentSentenceIndex >= chapterSentences.size then
      currentSentenceIndex = 0

    playCurrentSentence()

  private def playCurrentSentence(): Unit =
    if chapterSentences.isEmpty || currentSentenceIndex < 0 || currentSentenceIndex >= chapterSentences.size then
      stopTtsCompletely()
      return

    val sentenceIndex = currentSentenceIndex
    val sentenceText = chapterSentences(sentenceIndex)
    if sentenceText.trim.isEmpty then
      if sentenceIndex + 1 < chapterSentences.size then
        currentSentenceIndex += 1
        playCurrentSentence()
      else
        stopTtsCompletely()
      return

    isTtsPlaying = true
    updateListenButtonState()
    ttsOverlay.setPlaying(true)
    ttsOverlay.updateChapter(currentChapterTitle)
    ttsOverlay.updateSpokenText(sentenceText, -1, -1)

    // 立即在正文 WebView 中高亮当前句并平滑滚动到可视区中心
    highlightSentenceInWebView(sentenceIndex, -1, -1)

    // 预合成后两句，实现句间零延迟无缝衔接
    if (sentenceIndex + 1 < chapterSentences.size)
      ttsPlayer.prefetch(chapterSentences(sentenceIndex + 1), currentTtsVoice, currentTtsRate)
    if (sentenceIndex + 2 < chapterSentences.size)
      ttsPlayer.prefetch(chapterSentences(sentenceIndex + 2), currentTtsVoice, currentTtsRate)

    val totalCount = Math.max(1, chapterSentences.size)
    val baseProgress = (sentenceIndex.toDouble / totalCount) * 100.0
    ttsOverlay.updateProgress(baseProgress)

    ttsPlayer.play(
      text = sentenceText,
      voice = currentTtsVoice,
      rate = currentTtsRate,
      onStart = () => {
        isTtsPlaying = true
        updateListenButtonState()
        ttsOverlay.setPlaying(true)
        highlightSentenceInWebView(sentenceIndex, -1, -1)
      },
      onWordBoundary = wb => {
        highlightSentenceInWebView(sentenceIndex, wb.startChar, wb.endChar)
        ttsOverlay.updateSpokenText(sentenceText, wb.startChar, wb.endChar)
      },
      onProgress = sentPct => {
        val chapterPct = ((sentenceIndex.toDouble + (sentPct / 100.0)) / totalCount) * 100.0
        ttsOverlay.updateProgress(chapterPct)
      },
      onFinish = () => {
        if (sentenceIndex + 1 < chapterSentences.size) {
          currentSentenceIndex = sentenceIndex + 1
          playCurrentSentence()
        } else if (currentChapterIndex + 1 < contentReader.chapters.size) {
          autoPlayOnChapterLoad = true
          currentSentenceIndex = 0
          loadChapter(currentChapterIndex + 1)
        } else {
          stopTtsCompletely()
        }
      },
      onError = _ => {
        isTtsPlaying = false
        updateListenButtonState()
        ttsOverlay.setPlaying(false)
      }
    )

  // 从 WebView DOM 提取句子列表并包裹 span[data-vox-sent]
  private def prepareChapterSentencesFromDom(): Unit =
    try
      val countObj = webEngine.executeScript("window.__voxTts ? window.__voxTts.getSentenceCount() : 0")
      val count = countObj match
        case n: java.lang.Number => n.intValue()
        case s: String           => s.toIntOption.getOrElse(0)
        case _                   => 0
      if count > 0 then
        val buf = Vector.newBuilder[String]
        var i = 0
        while (i < count)
          val raw = webEngine.executeScript(s"window.__voxTts.getSentenceText($i)")
          val text = if (raw != null) raw.toString else ""
          buf += text
          i += 1
        chapterSentences = buf.result()
      else
        val rawHtml = contentReader.readChapterContent(currentChapterIndex)
        val plain = extractPlainText(rawHtml)
        chapterSentences = splitFallbackSentences(plain)
    catch
      case _: Throwable =>
        val rawHtml = contentReader.readChapterContent(currentChapterIndex)
        val plain = extractPlainText(rawHtml)
        chapterSentences = splitFallbackSentences(plain)

  private def splitFallbackSentences(text: String): Vector[String] =
    text
      .split("(?<=[。！？!?；;\\n])")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector

  private def highlightSentenceInWebView(sentIdx: Int, wordStart: Int, wordEnd: Int): Unit =
    try
      webEngine.executeScript(s"window.__voxTts && window.__voxTts.highlightSentence($sentIdx, $wordStart, $wordEnd);")
    catch
      case _: Throwable => ()

  private def clearWebViewHighlight(): Unit =
    try
      webEngine.executeScript("window.__voxTts && window.__voxTts.clearHighlight();")
    catch
      case _: Throwable => ()

  private def applyHighlightColorsToDom(): Unit =
    try
      webEngine.executeScript(
        s"window.__voxTts && window.__voxTts.setHighlightColor('$currentSentenceHighlightRgba', '$currentWordHighlightRgba');"
      )
    catch
      case _: Throwable => ()

  // 章节加载
  private def loadChapter(index: Int): Unit =
    if contentReader.chapters.isEmpty then
      webEngine.loadContent("<html><body><p>本书籍未包含可读章节内容</p></body></html>", "text/html")
      return

    currentChapterIndex = Math.max(0, Math.min(index, contentReader.chapters.size - 1))
    currentSentenceIndex = 0
    chapterSentences = Vector.empty
    val ch = contentReader.chapters(currentChapterIndex)
    lblChapterTitle.text = ch.title
    lblPageIndicator.text = s"第 ${currentChapterIndex + 1} / ${contentReader.chapters.size} 章"

    val rawHtml = contentReader.readChapterContent(currentChapterIndex)
    val styledHtml = injectReaderStyles(rawHtml)
    webEngine.loadContent(styledHtml, "text/html")

  private def prevChapter(): Unit =
    if currentChapterIndex > 0 then
      val wasPlaying = isTtsPlaying
      stopTtsCompletely()
      autoPlayOnChapterLoad = wasPlaying
      loadChapter(currentChapterIndex - 1)

  private def nextChapter(): Unit =
    if currentChapterIndex < contentReader.chapters.size - 1 then
      val wasPlaying = isTtsPlaying
      stopTtsCompletely()
      autoPlayOnChapterLoad = wasPlaying
      loadChapter(currentChapterIndex + 1)

  private def adjustFontSize(delta: Int): Unit =
    currentFontSize = Math.max(12, Math.min(36, currentFontSize + delta))
    val savedSentenceIdx = currentSentenceIndex
    loadChapter(currentChapterIndex)
    currentSentenceIndex = savedSentenceIdx

  private def cycleReadingTheme(): Unit =
    currentReadingTheme = currentReadingTheme match
      case "light" => "sepia"
      case "sepia" => "dark"
      case _       => "light"
    val savedSentenceIdx = currentSentenceIndex
    loadChapter(currentChapterIndex)
    currentSentenceIndex = savedSentenceIdx

  private def extractPlainText(html: String): String =
    html
      .replaceAll("(?s)<script.*?</script>", " ")
      .replaceAll("(?s)<style.*?</style>", " ")
      .replaceAll("<[^>]*>", " ")
      .replaceAll("&nbsp;", " ")
      .replaceAll("&lt;", "<")
      .replaceAll("&gt;", ">")
      .replaceAll("&amp;", "&")
      .replaceAll("\\s+", " ")
      .trim

  private def injectReaderStyles(html: String): String =
    val (bg, fg, linkColor) = currentReadingTheme match
      case "sepia" => ("#FBF0D9", "#443425", "#8F5902")
      case "dark"  => ("#1A1C1E", "#DDE1E6", "#78B6FF")
      case _       => ("#FFFFFF", "#252A32", "#00A5F5")

    val styleAndScriptTag =
      s"""<style>
         |:root {
         |  --vox-tts-highlight: $currentSentenceHighlightRgba;
         |  --vox-tts-word-highlight: $currentWordHighlightRgba;
         |}
         |body {
         |  background-color: $bg !important;
         |  color: $fg !important;
         |  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif;
         |  font-size: ${currentFontSize}px !important;
         |  line-height: 1.85 !important;
         |  padding: 40px 60px !important;
         |  max-width: 820px;
         |  margin: 0 auto;
         |}
         |a { color: $linkColor !important; }
         |img { max-width: 100%; height: auto; }
         |.vox-tts-sent {
         |  border-radius: 3px;
         |  transition: background-color 0.12s ease;
         |}
         |.vox-tts-sentence-active {
         |  background-color: var(--vox-tts-highlight) !important;
         |  border-radius: 4px;
         |  box-shadow: 0 0 0 2px var(--vox-tts-highlight);
         |}
         |mark.vox-tts-word-active {
         |  background-color: var(--vox-tts-word-highlight) !important;
         |  color: inherit !important;
         |  border-radius: 3px;
         |  padding: 0 1px;
         |}
         |</style>
         |<script>
         |(function() {
         |  window.__voxTts = {
         |    sentences: [],
         |    activeSentIdx: -1,
         |    prepared: false,
         |    prepare: function() {
         |      if (this.prepared) return this.sentences.length;
         |      if (!document.body) return 0;
         |      this.prepared = true;
         |      this.sentences = [];
         |      var sentIdx = 0;
         |      var currentSentText = "";
         |
         |      function isSkippedTag(node) {
         |        while (node && node !== document.body) {
         |          var tag = node.tagName;
         |          if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return true;
         |          node = node.parentNode;
         |        }
         |        return false;
         |      }
         |
         |      var blockTags = {
         |        'P':1, 'DIV':1, 'H1':1, 'H2':1, 'H3':1, 'H4':1, 'H5':1, 'H6':1,
         |        'LI':1, 'BLOCKQUOTE':1, 'SECTION':1, 'ARTICLE':1, 'HEADER':1,
         |        'FOOTER':1, 'PRE':1, 'TD':1, 'TH':1, 'DT':1, 'DD':1, 'FIGCAPTION':1
         |      };
         |
         |      function getBlockAncestor(node) {
         |        var cur = node.parentNode;
         |        while (cur && cur !== document.body) {
         |          if (blockTags[cur.tagName]) return cur;
         |          cur = cur.parentNode;
         |        }
         |        return document.body;
         |      }
         |
         |      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
         |      var textNodes = [];
         |      var n;
         |      while ((n = walker.nextNode())) {
         |        if (!isSkippedTag(n) && n.nodeValue && n.nodeValue.trim().length > 0) {
         |          textNodes.push(n);
         |        }
         |      }
         |
         |      var lastBlock = null;
         |      for (var i = 0; i < textNodes.length; i++) {
         |        var tNode = textNodes[i];
         |        var block = getBlockAncestor(tNode);
         |        if (lastBlock !== null && block !== lastBlock && currentSentText.trim().length > 0) {
         |          this.sentences.push(currentSentText);
         |          sentIdx++;
         |          currentSentText = "";
         |        }
         |        lastBlock = block;
         |
         |        var raw = tNode.nodeValue;
         |        var parts = raw.match(/[^。！？!?；;\\n]+[。！？!?；;\\n]*|[。！？!?；;\\n]+/g) || [raw];
         |        var frag = document.createDocumentFragment();
         |
         |        for (var j = 0; j < parts.length; j++) {
         |          var part = parts[j];
         |          var span = document.createElement('span');
         |          span.className = 'vox-tts-sent';
         |          span.setAttribute('data-vox-sent', String(sentIdx));
         |          span.setAttribute('data-vox-start', String(currentSentText.length));
         |          span.setAttribute('data-vox-end', String(currentSentText.length + part.length));
         |          span.textContent = part;
         |          frag.appendChild(span);
         |
         |          currentSentText += part;
         |          if (/[。！？!?；;\\n]$$/.test(part) && currentSentText.trim().length > 0) {
         |            this.sentences.push(currentSentText);
         |            sentIdx++;
         |            currentSentText = "";
         |          }
         |        }
         |        if (tNode.parentNode) {
         |          tNode.parentNode.replaceChild(frag, tNode);
         |        }
         |      }
         |      if (currentSentText.trim().length > 0) {
         |        this.sentences.push(currentSentText);
         |      }
         |      return this.sentences.length;
         |    },
         |    getSentenceCount: function() {
         |      this.prepare();
         |      return this.sentences.length;
         |    },
         |    getSentenceText: function(idx) {
         |      this.prepare();
         |      return (idx >= 0 && idx < this.sentences.length) ? this.sentences[idx] : "";
         |    },
         |    clearWordHighlight: function() {
         |      var marks = document.querySelectorAll('mark.vox-tts-word-active');
         |      for (var i = 0; i < marks.length; i++) {
         |        var m = marks[i];
         |        var parent = m.parentNode;
         |        if (parent) {
         |          parent.replaceChild(document.createTextNode(m.textContent), m);
         |          parent.normalize();
         |        }
         |      }
         |    },
         |    clearHighlight: function() {
         |      this.clearWordHighlight();
         |      var prev = document.querySelectorAll('.vox-tts-sentence-active');
         |      for (var i = 0; i < prev.length; i++) {
         |        prev[i].classList.remove('vox-tts-sentence-active');
         |      }
         |      this.activeSentIdx = -1;
         |    },
         |    highlightSentence: function(idx, wordStart, wordEnd) {
         |      this.prepare();
         |      this.clearWordHighlight();
         |      if (this.activeSentIdx !== idx) {
         |        var prev = document.querySelectorAll('.vox-tts-sentence-active');
         |        for (var i = 0; i < prev.length; i++) {
         |          prev[i].classList.remove('vox-tts-sentence-active');
         |        }
         |        this.activeSentIdx = idx;
         |      }
         |      var spans = document.querySelectorAll('.vox-tts-sent[data-vox-sent="' + idx + '"]');
         |      if (!spans || spans.length === 0) return false;
         |      for (var k = 0; k < spans.length; k++) {
         |        spans[k].classList.add('vox-tts-sentence-active');
         |      }
         |      if (typeof wordStart === 'number' && typeof wordEnd === 'number' && wordEnd > wordStart) {
         |        for (var s = 0; s < spans.length; s++) {
         |          var sp = spans[s];
         |          var spStart = parseInt(sp.getAttribute('data-vox-start') || '0', 10);
         |          var spEnd = parseInt(sp.getAttribute('data-vox-end') || '0', 10);
         |          var overlapStart = Math.max(wordStart, spStart);
         |          var overlapEnd = Math.min(wordEnd, spEnd);
         |          if (overlapEnd > overlapStart && sp.firstChild && sp.firstChild.nodeType === 3) {
         |            var fullText = sp.textContent;
         |            var relStart = overlapStart - spStart;
         |            var relEnd = overlapEnd - spStart;
         |            if (relStart >= 0 && relEnd <= fullText.length) {
         |              var before = fullText.substring(0, relStart);
         |              var mid = fullText.substring(relStart, relEnd);
         |              var after = fullText.substring(relEnd);
         |              sp.textContent = '';
         |              if (before.length > 0) sp.appendChild(document.createTextNode(before));
         |              var mark = document.createElement('mark');
         |              mark.className = 'vox-tts-word-active';
         |              mark.textContent = mid;
         |              sp.appendChild(mark);
         |              if (after.length > 0) sp.appendChild(document.createTextNode(after));
         |            }
         |          }
         |        }
         |      }
         |      var first = spans[0];
         |      var rect = first.getBoundingClientRect();
         |      var vh = window.innerHeight || document.documentElement.clientHeight;
         |      if (rect.top < vh * 0.18 || rect.bottom > vh * 0.82) {
         |        first.scrollIntoView({ behavior: 'smooth', block: 'center' });
         |      }
         |      return true;
         |    },
         |    setHighlightColor: function(sentColor, wordColor) {
         |      document.documentElement.style.setProperty('--vox-tts-highlight', sentColor);
         |      document.documentElement.style.setProperty('--vox-tts-word-highlight', wordColor);
         |    }
         |  };
         |})();
         |</script>""".stripMargin

    if html.contains("</head>") then
      html.replace("</head>", s"$styleAndScriptTag</head>")
    else
      s"<html><head>$styleAndScriptTag</head><body>$html</body></html>"

  private def createExpandingSpacer(): Region =
    val r = new Region()
    HBox.setHgrow(r, Priority.Always)
    r

  private def createToolbarButton(icon: Feather, tooltipText: String, action: () => Unit): Button =
    val fIcon = new FontIcon(icon)
    fIcon.setIconSize(20)
    fIcon.setIconColor(javafx.scene.paint.Color.web(ToolbarInk))
    fIcon.setMouseTransparent(true)

    val baseStyle =
      "-fx-background-color: transparent; " +
      "-fx-background-radius: 8px; " +
      "-fx-padding: 0; " +
      "-fx-alignment: center; " +
      "-fx-cursor: hand;"

    val hoverStyle =
      "-fx-background-color: rgba(0, 0, 0, 0.06); " +
      "-fx-background-radius: 8px; " +
      "-fx-padding: 0; " +
      "-fx-alignment: center; " +
      "-fx-cursor: hand;"

    new Button:
      graphic = fIcon
      prefWidth = 48
      prefHeight = 42
      minWidth = 48
      minHeight = 42
      maxWidth = 48
      maxHeight = 42
      style = baseStyle
      tooltip = new Tooltip(tooltipText)
      onMouseEntered = _ => { style = hoverStyle }
      onMouseExited = _ => { style = baseStyle }
      onAction = _ => action()

  // 初始化加载首章
  loadChapter(0)
