package com.roll0the1dice.voxread.desktop.ui.components

import java.io.File
import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{ContextMenu, Label, MenuItem, Tooltip}
import scalafx.scene.effect.DropShadow
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.input.{MouseButton, MouseEvent}
import scalafx.scene.layout.{Priority, StackPane, VBox}
import scalafx.scene.paint.Color
import scalafx.scene.shape.Rectangle
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon
import com.roll0the1dice.voxread.desktop.data.model.Book

/**
 * 书架图书卡片，对齐 Android 原版布局 (item_recycle_book.xml):
 * - CardView: 6dp 圆角，1dp 阴影，卡片间距
 * - 封面比例严格 2:3 黄金比例 (140px * 210px)
 * - 封面顶部半透明阴影徽标 bookshelf_progressLabel ("已读 xx%")
 * - 标题文字限制最多 2 行截断 (12sp / 13px)
 */
class BookCard(
  val book: Book,
  onOpen: Book => Unit,
  onDelete: Book => Unit
) extends VBox:

  // 卡片外层属性 (对齐 CardView)
  spacing = 0
  alignment = Pos.TopCenter
  prefWidth = 148
  maxWidth = 148
  cursor = scalafx.scene.Cursor.Hand

  // Material 3 卡片风格：白色背景、6dp 圆角、1dp 柔和阴影
  style =
    "-fx-background-color: #FFFFFF; " +
    "-fx-background-radius: 6px; " +
    "-fx-border-radius: 6px; " +
    "-fx-border-color: rgba(0, 0, 0, 0.08); " +
    "-fx-border-width: 1px;"

  effect = new DropShadow:
    radius = 3.0
    offsetX = 0.0
    offsetY = 1.0
    color = Color.rgb(0, 0, 0, 0.12)

  // 1. 封面容器 (保持 2:3 黄金比例，140 x 210)
  private val coverWidth = 146.0
  private val coverHeight = 219.0 // 146 * 1.5 = 219 (2:3)

  private val coverStack = new StackPane:
    prefWidth = coverWidth
    prefHeight = coverHeight
    maxWidth = coverWidth
    maxHeight = coverHeight
    style = "-fx-background-color: #ECEFF1; -fx-background-radius: 6px 6px 0 0;"
    alignment = Pos.Center

    // 裁剪圆角，只裁顶部两个角
    val clipRect = new Rectangle:
      width = coverWidth
      height = coverHeight
      arcWidth = 12
      arcHeight = 12
    clip = clipRect

    val coverImgView = new ImageView:
      fitWidth = coverWidth
      fitHeight = coverHeight
      preserveRatio = true
      smooth = true

    val placeholder = new VBox:
      alignment = Pos.Center
      spacing = 6
      val iconLabel = new Label:
        val fIcon = new FontIcon(Feather.BOOK)
        fIcon.setIconSize(40)
        fIcon.setIconColor(javafx.scene.paint.Color.web("#90A4AE"))
        graphic = fIcon
      val fallbackText = new Label(book.displayName.take(10)):
        style = "-fx-font-size: 11px; -fx-text-fill: #90A4AE;"
      children = Seq(iconLabel, fallbackText)

    book.cover.filter(path => new File(path).exists()) match
      case Some(path) =>
        try
          val img = new Image(new File(path).toURI.toString, coverWidth, coverHeight, true, true)
          coverImgView.image = img
          children = Seq(coverImgView)
        catch
          case _: Throwable =>
            children = Seq(placeholder)
      case None =>
        children = Seq(placeholder)

    // 进度胶囊徽标 (bookshelf_progressLabel)
    parseReadingFraction(book.progression).foreach { fraction =>
      val percent = (fraction * 100).round.toInt.max(0).min(100)
      val progressBadge = new Label(s"已读 $percent%"):
        style =
          "-fx-text-fill: #FFFFFF; " +
          "-fx-font-size: 10px; " +
          "-fx-font-weight: bold; " +
          "-fx-background-color: rgba(0, 0, 0, 0.55); " +
          "-fx-background-radius: 10px; " +
          "-fx-padding: 3 8 3 8;"
        effect = new DropShadow:
          radius = 3.0
          offsetY = 1.0
          color = Color.rgb(0, 0, 0, 0.8)
      StackPane.setAlignment(progressBadge, Pos.TopCenter)
      StackPane.setMargin(progressBadge, Insets(8, 0, 0, 0))
      children.add(progressBadge)
    }

  // 2. 标题区 (bookshelf_titleText: 12sp, 2行截断, textColorPrimary)
  private val titleLabel = new Label(book.displayName):
    maxWidth = 132
    prefHeight = 36
    maxHeight = 36
    wrapText = true
    alignment = Pos.TopLeft
    style =
      "-fx-font-size: 12px; " +
      "-fx-font-weight: 500; " +
      "-fx-text-fill: #212121; " +
      "-fx-line-spacing: 1px;"
    tooltip = new Tooltip(book.displayName)

  private val titleBox = new VBox(titleLabel):
    padding = Insets(8, 8, 8, 8)
    alignment = Pos.TopLeft

  children = Seq(coverStack, titleBox)

  // 3. 上下文菜单与点击
  private val contextMenu = new ContextMenu()
  private val openItem = new MenuItem("打开阅读"):
    graphic = new FontIcon(Feather.BOOK_OPEN)
    onAction = _ => onOpen(book)
  private val deleteItem = new MenuItem("从书架删除"):
    graphic = new FontIcon(Feather.TRASH_2)
    onAction = _ => onDelete(book)
  contextMenu.items ++= Seq(openItem, deleteItem)

  onMouseClicked = (e: MouseEvent) => {
    if (e.button == MouseButton.Primary && e.clickCount == 1)
      onOpen(book)
    else if (e.button == MouseButton.Secondary)
      contextMenu.show(this, e.screenX, e.screenY)
  }

  // 解析存储的进度百分比
  private def parseReadingFraction(progression: Option[String]): Option[Double] =
    progression.flatMap { prog =>
      if prog.trim.isEmpty || prog == "{}" then None
      else
        val totalProgRegex = """"totalProgression":\s*([0-9.]+)""".r
        val progRegex = """"progression":\s*([0-9.]+)""".r
        val numRegex = """^([0-9.]+)$""".r
        totalProgRegex.findFirstMatchIn(prog).map(_.group(1).toDouble)
          .orElse(progRegex.findFirstMatchIn(prog).map(_.group(1).toDouble))
          .orElse(numRegex.findFirstMatchIn(prog).map(_.group(1).toDouble))
          .map(p => math.max(0.0, math.min(1.0, p)))
    }
