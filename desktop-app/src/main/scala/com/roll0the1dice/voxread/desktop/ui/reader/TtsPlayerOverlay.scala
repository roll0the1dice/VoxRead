package com.roll0the1dice.voxread.desktop.ui.reader

import java.io.File
import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control._
import scalafx.scene.effect.DropShadow
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.layout._
import scalafx.scene.paint.Color
import scalafx.scene.shape.Rectangle
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.tts.{EdgeTtsConstants, EdgeVoiceOption}

case class TtsHighlightPreset(
  label: String,
  sentenceRgba: String,
  wordRgba: String
)

/**
 * TTS AI 语音朗读全屏面板，1:1 对齐 Android 原版 TtsPlayerScreen.kt:
 * - 经典配色：PlayerBackground(#F4F5F7), PlayerInk(#60656E), PlayerBlue(#00A5F5)
 * - 顶部栏：收起播放器 (ic_player_collapse)、"AI 阅读" 胶囊标签、朗读设置
 * - 中部：2:3 封面图 (带 8dp 阴影)、22sp 粗体章节标题、实时同步高亮字幕框
 * - 快捷操作行：定时关闭、音色(人物)选择、语速调节、高亮设置
 * - 朗读进度条与百分比显示
 * - 核心控制栏：原文、上一句、76dp PlayerBlue 大号主播放按钮、下一句、目录
 * - 底部卡片："返回原文后继续收听" 与 "结束朗读"
 * - 内置发音人选择弹窗 (对齐 EdgeTtsVoices.kt 全部精选人物音色)
 */
class TtsPlayerOverlay(
  val book: Book,
  var chapterTitle: String,
  onDismiss: () => Unit,
  onTogglePlay: () => Unit,
  onPrev: () => Unit,
  onNext: () => Unit,
  onStop: () => Unit,
  onOutline: () => Unit,
  onVoiceChanged: String => Unit = _ => (),
  onSpeedChanged: Double => Unit = _ => (),
  onHighlightColorChanged: (String, String) => Unit = (_, _) => ()
) extends StackPane:

  // 原版 Material 规范色彩定义
  val PlayerBackground = "#F4F5F7"
  val PlayerInk = "#60656E"
  val PlayerBlue = "#00A5F5"
  val TitleColor = "#252A32"

  style = s"-fx-background-color: $PlayerBackground;"

  private var isPlaying = false
  private var currentVoice: EdgeVoiceOption = EdgeTtsConstants.BuiltInVoices.head
  private var currentSpeed = 1.0
  private val speeds = Seq(0.75, 1.0, 1.25, 1.5, 2.0)
  private var currentTimerIndex = 0
  private val timerOptions = Seq("定时关闭", "15分钟", "30分钟", "60分钟")

  // 对齐 Android TtsHighlightColor.kt 四种高亮配色
  private val highlightPresets = Seq(
    TtsHighlightPreset("高亮: 柔和黄", "rgba(255, 220, 40, 0.45)", "rgba(255, 180, 0, 0.80)"),
    TtsHighlightPreset("高亮: 护眼绿", "rgba(76, 175, 80, 0.36)", "rgba(56, 142, 60, 0.72)"),
    TtsHighlightPreset("高亮: 天空蓝", "rgba(33, 150, 243, 0.34)", "rgba(25, 118, 210, 0.68)"),
    TtsHighlightPreset("高亮: 珊瑚红", "rgba(244, 67, 54, 0.32)", "rgba(211, 47, 47, 0.68)")
  )
  private var currentHighlightIndex = 0

  // 1. 顶部操作栏
  private val btnCollapse = new Button:
    prefWidth = 40
    prefHeight = 40
    minWidth = 40
    minHeight = 40
    maxWidth = 40
    maxHeight = 40
    val icon = new FontIcon(Feather.CHEVRON_DOWN)
    icon.setIconSize(26)
    icon.setIconColor(javafx.scene.paint.Color.web(PlayerInk))
    icon.setMouseTransparent(true)
    graphic = icon
    style = "-fx-background-color: transparent; -fx-padding: 0; -fx-alignment: center; -fx-cursor: hand;"
    tooltip = new Tooltip("收起播放器")
    onAction = _ => onDismiss()

  private val badgeAiLabel = new Label("AI 阅读"):
    style =
      s"-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: $TitleColor; " +
      "-fx-background-color: #FFFFFF; " +
      "-fx-background-radius: 20px; " +
      "-fx-padding: 8 28 8 28;"
    effect = new DropShadow:
      radius = 4.0
      offsetY = 1.0
      color = Color.rgb(0, 0, 0, 0.08)

  private val btnSettings = new Button:
    prefWidth = 40
    prefHeight = 40
    minWidth = 40
    minHeight = 40
    maxWidth = 40
    maxHeight = 40
    val icon = new FontIcon(Feather.SETTINGS)
    icon.setIconSize(22)
    icon.setIconColor(javafx.scene.paint.Color.web(PlayerInk))
    icon.setMouseTransparent(true)
    graphic = icon
    style = "-fx-background-color: transparent; -fx-padding: 0; -fx-alignment: center; -fx-cursor: hand;"
    tooltip = new Tooltip("选择朗读人物 / 音色设置")
    onAction = _ => openVoicePickerModal()

  private val topSpacer1 = new Region()
  private val topSpacer2 = new Region()
  HBox.setHgrow(topSpacer1, Priority.Always)
  HBox.setHgrow(topSpacer2, Priority.Always)

  private val topBar = new HBox:
    alignment = Pos.Center
    padding = Insets(12, 24, 12, 24)
    children = Seq(btnCollapse, topSpacer1, badgeAiLabel, topSpacer2, btnSettings)

  // 2. 封面 (对齐 TtsPlayerScreen 中带有 8dp 阴影与 2:3 比例的封面)
  private val coverHeight = 195.0
  private val coverWidth = coverHeight * 0.70 // 黄金比例 ~136px

  private val coverContainer = new StackPane:
    prefWidth = coverWidth
    prefHeight = coverHeight
    maxWidth = coverWidth
    maxHeight = coverHeight
    alignment = Pos.Center
    style = "-fx-background-color: #FFFFFF; -fx-background-radius: 4px;"
    effect = new DropShadow:
      radius = 12.0
      offsetY = 6.0
      color = Color.rgb(0, 0, 0, 0.22)

    val clipRect = new Rectangle:
      width = coverWidth
      height = coverHeight
      arcWidth = 8
      arcHeight = 8
    clip = clipRect

    val coverImgView = new ImageView:
      fitWidth = coverWidth
      fitHeight = coverHeight
      preserveRatio = true
      smooth = true

    val placeholderLabel = new Label(book.displayName):
      style = s"-fx-font-size: 13px; -fx-text-fill: $PlayerInk; -fx-padding: 16px; -fx-text-alignment: center;"
      wrapText = true
      alignment = Pos.Center

    book.cover.filter(path => new File(path).exists()) match
      case Some(path) =>
        try
          coverImgView.image = new Image(new File(path).toURI.toString, coverWidth, coverHeight, true, true)
          children = Seq(coverImgView)
        catch
          case _: Throwable => children = Seq(placeholderLabel)
      case None =>
        children = Seq(placeholderLabel)

  // 3. 章节标题 (22sp 粗体居中) + 实时同步高亮字幕预览
  private val lblChapter = new Label(if chapterTitle.nonEmpty then chapterTitle else book.displayName):
    style = s"-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: $TitleColor;"
    maxWidth = 600
    alignment = Pos.Center

  private val txtBefore = new scalafx.scene.text.Text(""):
    style = s"-fx-fill: $PlayerInk; -fx-font-size: 14px;"
  private val txtActive = new scalafx.scene.text.Text(""):
    style = s"-fx-fill: $PlayerBlue; -fx-font-size: 15px; -fx-font-weight: bold; -fx-underline: true;"
  private val txtAfter = new scalafx.scene.text.Text(""):
    style = s"-fx-fill: $PlayerInk; -fx-font-size: 14px;"

  private val spokenTextFlow = new scalafx.scene.text.TextFlow(txtBefore, txtActive, txtAfter):
    textAlignment = scalafx.scene.text.TextAlignment.Center
    lineSpacing = 4.0
    maxWidth = 520

  private val spokenSubtitleBox = new StackPane:
    maxWidth = 540
    minHeight = 52
    prefHeight = 52
    padding = Insets(8, 16, 8, 16)
    alignment = Pos.Center
    style =
      "-fx-background-color: rgba(255, 255, 255, 0.78); " +
      "-fx-background-radius: 10px; " +
      "-fx-border-radius: 10px;"
    children = Seq(spokenTextFlow)

  // 4. 四个快捷操作按钮 (PlayerAction: 定时关闭、音色、语速、高亮)
  private val btnTimer = createActionItem(Feather.CLOCK, "定时关闭")
  private val btnVoice = createActionItem(Feather.USER, s"音色: ${currentVoice.displayName}")
  private val btnSpeed = createActionItem(Feather.COMPASS, "语速 1.0x")
  private val btnHighlight = createActionItem(Feather.SLIDERS, highlightPresets.head.label)

  btnTimer.onMouseClicked = _ => {
    currentTimerIndex = (currentTimerIndex + 1) % timerOptions.length
    updateActionItem(btnTimer, Feather.CLOCK, timerOptions(currentTimerIndex))
  }

  btnVoice.onMouseClicked = _ => {
    openVoicePickerModal()
  }

  btnSpeed.onMouseClicked = _ => {
    val idx = (speeds.indexOf(currentSpeed) + 1) % speeds.length
    currentSpeed = speeds(idx)
    updateActionItem(btnSpeed, Feather.COMPASS, s"语速 ${currentSpeed}x")
    onSpeedChanged(currentSpeed)
  }

  btnHighlight.onMouseClicked = _ => {
    currentHighlightIndex = (currentHighlightIndex + 1) % highlightPresets.length
    val preset = highlightPresets(currentHighlightIndex)
    updateActionItem(btnHighlight, Feather.SLIDERS, preset.label)
    onHighlightColorChanged(preset.sentenceRgba, preset.wordRgba)
  }

  private val actionRow = new HBox(36):
    alignment = Pos.Center
    maxWidth = 540
    children = Seq(btnTimer, btnVoice, btnSpeed, btnHighlight)

  // 5. 朗读进度条
  private val lblProgressTitle = new Label("朗读进度"):
    style = s"-fx-font-size: 12px; -fx-text-fill: $PlayerInk;"
  private val lblProgressPercent = new Label("0%"):
    style = s"-fx-font-size: 12px; -fx-text-fill: $PlayerInk;"

  private val progressSpacer = new Region()
  HBox.setHgrow(progressSpacer, Priority.Always)

  private val progressTextRow = new HBox:
    maxWidth = 480
    children = Seq(lblProgressTitle, progressSpacer, lblProgressPercent)

  val progressSlider = new Slider(0, 100, 0):
    maxWidth = 480
    prefWidth = 480
    style = s"-fx-accent: $PlayerBlue;"

  progressSlider.value.onChange { (_, _, newVal) =>
    lblProgressPercent.text = s"${newVal.intValue()}%"
  }

  // 6. 核心播放控制排 (原文、上一句、76dp 主播放按钮、下一句、目录)
  private val btnText = createActionItem(Feather.BOOK_OPEN, "原文")
  btnText.onMouseClicked = _ => onDismiss()

  private val btnPrev = new Button:
    prefWidth = 48
    prefHeight = 48
    minWidth = 48
    minHeight = 48
    val icon = new FontIcon(Feather.SKIP_BACK)
    icon.setIconSize(28)
    icon.setIconColor(javafx.scene.paint.Color.web(PlayerInk))
    icon.setMouseTransparent(true)
    graphic = icon
    style = "-fx-background-color: transparent; -fx-padding: 0; -fx-alignment: center; -fx-cursor: hand;"
    tooltip = new Tooltip("上一句")
    onAction = _ => onPrev()

  // 76dp 大号主播放按钮 (PlayerBlue 圆形)
  private val playIcon = new FontIcon(Feather.PLAY)
  playIcon.setIconSize(34)
  playIcon.setIconColor(javafx.scene.paint.Color.WHITE)
  playIcon.setMouseTransparent(true)

  private val btnMainPlay = new Button:
    prefWidth = 72
    prefHeight = 72
    minWidth = 72
    minHeight = 72
    maxWidth = 72
    maxHeight = 72
    graphic = playIcon
    style =
      s"-fx-background-color: $PlayerBlue; " +
      "-fx-background-radius: 36px; " +
      "-fx-border-radius: 36px; " +
      "-fx-padding: 0; " +
      "-fx-alignment: center; " +
      "-fx-cursor: hand;"
    effect = new DropShadow:
      radius = 10.0
      offsetY = 4.0
      color = Color.rgb(0, 165, 245, 0.45)
    onAction = _ => onTogglePlay()

  private val btnNext = new Button:
    prefWidth = 48
    prefHeight = 48
    minWidth = 48
    minHeight = 48
    val icon = new FontIcon(Feather.SKIP_FORWARD)
    icon.setIconSize(28)
    icon.setIconColor(javafx.scene.paint.Color.web(PlayerInk))
    icon.setMouseTransparent(true)
    graphic = icon
    style = "-fx-background-color: transparent; -fx-padding: 0; -fx-alignment: center; -fx-cursor: hand;"
    tooltip = new Tooltip("下一句")
    onAction = _ => onNext()

  private val btnOutline = createActionItem(Feather.LIST, "目录")
  btnOutline.onMouseClicked = _ => onOutline()

  private val controlRow = new HBox(28):
    alignment = Pos.Center
    maxWidth = 500
    children = Seq(btnText, btnPrev, btnMainPlay, btnNext, btnOutline)

  // 7. 底部提示与结束朗读卡片 (RoundedCornerShape 20dp)
  private val bottomCard = new HBox:
    maxWidth = 520
    prefHeight = 64
    alignment = Pos.CenterLeft
    padding = Insets(12, 20, 12, 20)
    style =
      "-fx-background-color: #FFFFFF; " +
      "-fx-background-radius: 16px; " +
      "-fx-border-radius: 16px;"
    effect = new DropShadow:
      radius = 4.0
      offsetY = 2.0
      color = Color.rgb(0, 0, 0, 0.06)

    val lblBookName = new Label(book.displayName):
      style = s"-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: $TitleColor;"
      maxWidth = 320
      mouseTransparent = true
    val lblHint = new Label("点击此处或「原文」返回页面查看同步高亮"):
      style = s"-fx-font-size: 11px; -fx-text-fill: $PlayerInk;"
      mouseTransparent = true
    val leftText = new VBox(2, lblBookName, lblHint):
      style = "-fx-cursor: hand;"
      pickOnBounds = true
      onMouseClicked = _ => onDismiss()

    val bSpacer = new Region()
    HBox.setHgrow(bSpacer, Priority.Always)

    val btnStop = new Button("结束朗读"):
      style = s"-fx-background-color: transparent; -fx-text-fill: $PlayerBlue; -fx-font-weight: bold; -fx-cursor: hand; -fx-font-size: 13px;"
      onAction = _ => onStop()

    children = Seq(leftText, bSpacer, btnStop)

  // 整体主容器
  private val centerBox = new VBox(14):
    alignment = Pos.Center
    padding = Insets(0, 32, 20, 32)
    children = Seq(
      coverContainer,
      lblChapter,
      spokenSubtitleBox,
      actionRow,
      progressTextRow,
      progressSlider,
      controlRow,
      bottomCard
    )

  private val rootLayout = new BorderPane:
    top = topBar
    center = centerBox

  // 8. 发音人 / 人物选择模态弹窗 (对齐 Android PlayerChoiceDialog + EdgeTtsVoices.kt)
  private val voiceListBox = new VBox(6):
    padding = Insets(8, 12, 8, 12)

  private val voiceScrollPane = new ScrollPane:
    content = voiceListBox
    fitToWidth = true
    hbarPolicy = ScrollPane.ScrollBarPolicy.Never
    vbarPolicy = ScrollPane.ScrollBarPolicy.AsNeeded
    prefHeight = 360
    maxHeight = 360
    style =
      "-fx-background-color: transparent; " +
      "-fx-background: #FFFFFF; " +
      "-fx-border-width: 0;"

  private lazy val voicePickerModal: StackPane =
    val headerTitle = new Label("选择 AI 朗读人物 (音色)"):
      style = s"-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: $TitleColor;"

    val headerSpacer = new Region()
    HBox.setHgrow(headerSpacer, Priority.Always)

    val btnCloseModal = new Button:
      prefWidth = 32
      prefHeight = 32
      minWidth = 32
      minHeight = 32
      maxWidth = 32
      maxHeight = 32
      val closeIcon = new FontIcon(Feather.X)
      closeIcon.setIconSize(18)
      closeIcon.setIconColor(javafx.scene.paint.Color.web(PlayerInk))
      closeIcon.setMouseTransparent(true)
      graphic = closeIcon
      style = "-fx-background-color: transparent; -fx-padding: 0; -fx-alignment: center; -fx-cursor: hand;"
      onAction = _ => { voicePickerModal.visible = false }

    val modalHeader = new HBox:
      alignment = Pos.CenterLeft
      padding = Insets(16, 20, 12, 20)
      style = "-fx-border-color: #E2E8F0; -fx-border-width: 0 0 1 0;"
      children = Seq(headerTitle, headerSpacer, btnCloseModal)

    val dialogCard = new VBox:
      maxWidth = 440
      maxHeight = 440
      style =
        "-fx-background-color: #FFFFFF; " +
        "-fx-background-radius: 16px; " +
        "-fx-border-radius: 16px;"
      effect = new DropShadow:
        radius = 20.0
        offsetY = 6.0
        color = Color.rgb(0, 0, 0, 0.25)
      children = Seq(modalHeader, voiceScrollPane)

    // 阻止点击卡片内部时触发遮罩层关闭
    dialogCard.onMouseClicked = e => e.consume()

    new StackPane:
      alignment = Pos.Center
      style = "-fx-background-color: rgba(0, 0, 0, 0.42);"
      visible = false
      children = Seq(dialogCard)
      onMouseClicked = _ => { visible = false }

  private def refreshVoiceListItems(): Unit =
    voiceListBox.children.clear()
    EdgeTtsConstants.BuiltInVoices.foreach { voice =>
      val isSelected = voice.shortName == currentVoice.shortName
      val rowBg = if isSelected then "#E0F2FE" else "#F8FAFC"
      val borderCol = if isSelected then PlayerBlue else "transparent"
      val nameColor = if isSelected then "#0077B6" else TitleColor

      val nameLbl = new Label(voice.displayName):
        style = s"-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: $nameColor;"
        mouseTransparent = true

      val genderBadge = new Label(voice.gender):
        val badgeBg = if voice.gender == "女声" then "#FCE7F3" else "#DBEAFE"
        val badgeFg = if voice.gender == "女声" then "#BE185D" else "#1D4ED8"
        style =
          s"-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: $badgeFg; " +
          s"-fx-background-color: $badgeBg; -fx-background-radius: 8px; -fx-padding: 2 7 2 7;"
        mouseTransparent = true

      val topLine = new HBox(8, nameLbl, genderBadge):
        alignment = Pos.CenterLeft
        mouseTransparent = true

      val descLbl = new Label(s"${voice.description} (${voice.shortName})"):
        style = s"-fx-font-size: 11px; -fx-text-fill: $PlayerInk;"
        mouseTransparent = true

      val leftCol = new VBox(3, topLine, descLbl):
        alignment = Pos.CenterLeft
        mouseTransparent = true

      val rowSpacer = new Region()
      HBox.setHgrow(rowSpacer, Priority.Always)

      val rightCheck = new Label:
        mouseTransparent = true
        if isSelected then
          val checkIcon = new FontIcon(Feather.CHECK_CIRCLE)
          checkIcon.setIconSize(20)
          checkIcon.setIconColor(javafx.scene.paint.Color.web(PlayerBlue))
          graphic = checkIcon

      val row = new HBox:
        alignment = Pos.CenterLeft
        padding = Insets(10, 14, 10, 14)
        pickOnBounds = true
        style =
          s"-fx-background-color: $rowBg; " +
          "-fx-background-radius: 10px; " +
          s"-fx-border-color: $borderCol; " +
          "-fx-border-radius: 10px; " +
          "-fx-border-width: 1.5px; " +
          "-fx-cursor: hand;"
        children = Seq(leftCol, rowSpacer, rightCheck)
        onMouseClicked = _ => {
          currentVoice = voice
          updateActionItem(btnVoice, Feather.USER, s"音色: ${voice.displayName}")
          voicePickerModal.visible = false
          onVoiceChanged(voice.shortName)
        }

      voiceListBox.children.add(row)
    }

  private def openVoicePickerModal(): Unit =
    refreshVoiceListItems()
    voicePickerModal.visible = true
    voicePickerModal.toFront()

  children = Seq(rootLayout, voicePickerModal)

  // 更新播放状态
  def setPlaying(playing: Boolean): Unit =
    isPlaying = playing
    if isPlaying then
      playIcon.setIconCode(Feather.PAUSE)
    else
      playIcon.setIconCode(Feather.PLAY)

  def updateChapter(title: String): Unit =
    chapterTitle = title
    lblChapter.text = title

  def updateSpokenText(sentence: String, wordStart: Int = -1, wordEnd: Int = -1): Unit =
    val clean = sentence.replaceAll("\\s+", " ").trim
    if clean.isEmpty then
      txtBefore.text = "点击播放按钮开始语音朗读（支持页面与字幕实时同步高亮）"
      txtActive.text = ""
      txtAfter.text = ""
    else if wordStart >= 0 && wordEnd > wordStart && wordEnd <= sentence.length then
      txtBefore.text = sentence.substring(0, wordStart).replaceAll("\\s+", " ")
      txtActive.text = sentence.substring(wordStart, wordEnd).replaceAll("\\s+", " ")
      txtAfter.text = sentence.substring(wordEnd).replaceAll("\\s+", " ")
    else
      txtBefore.text = ""
      txtActive.text = clean
      txtAfter.text = ""

  def updateProgress(percent: Double): Unit =
    progressSlider.value = percent
    lblProgressPercent.text = s"${percent.round.toInt}%"

  // 辅助组件构建
  private def createActionItem(icon: Feather, label: String): VBox =
    val fIcon = new FontIcon(icon)
    fIcon.setIconSize(20)
    fIcon.setIconColor(javafx.scene.paint.Color.web(PlayerInk))
    fIcon.setMouseTransparent(true)

    val lbl = new Label(label):
      style = s"-fx-font-size: 11px; -fx-font-weight: 500; -fx-text-fill: $PlayerInk;"
      mouseTransparent = true

    new VBox(6, fIcon, lbl):
      alignment = Pos.Center
      pickOnBounds = true
      style = "-fx-cursor: hand; -fx-background-color: transparent;"
      padding = Insets(6, 12, 6, 12)

  private def updateActionItem(box: VBox, icon: Feather, label: String): Unit =
    box.children.clear()
    val fIcon = new FontIcon(icon)
    fIcon.setIconSize(20)
    fIcon.setIconColor(javafx.scene.paint.Color.web(PlayerInk))
    fIcon.setMouseTransparent(true)
    val lbl = new Label(label):
      style = s"-fx-font-size: 11px; -fx-font-weight: 500; -fx-text-fill: $PlayerInk;"
      mouseTransparent = true
    box.children ++= Seq(fIcon, lbl)
