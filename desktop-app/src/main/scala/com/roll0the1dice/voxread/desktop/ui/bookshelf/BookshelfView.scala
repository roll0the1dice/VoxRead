package com.roll0the1dice.voxread.desktop.ui.bookshelf

import java.io.File
import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control._
import scalafx.scene.effect.DropShadow
import scalafx.scene.input.{DragEvent, TransferMode}
import scalafx.scene.layout._
import scalafx.scene.paint.Color
import scalafx.stage.FileChooser
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.ui.components.BookCard

/**
 * 书架视图，对齐 Android 原版 fragment_bookshelf.xml:
 * - 纯净的图书网格列表 (RecyclerView + GridLayoutManager)
 * - 右下角经典的 Material 悬浮操作按钮 (FloatingActionButton - FAB)
 * - 支持拖拽导入与居中空状态提示
 */
class BookshelfView(
  val viewModel: BookshelfViewModel,
  onOpenBook: Book => Unit
) extends StackPane:

  style = "-fx-background-color: #F8F9FA;"

  // 1. 书架卡片网格
  private val flowPane = new FlowPane:
    hgap = 16
    vgap = 18
    padding = Insets(20, 24, 80, 24) // 底部预留空间给 FAB 悬浮
    alignment = Pos.TopLeft

  // 2. 空状态提示 (居中展示)
  private val emptyIconView = new Label:
    val fIcon = new FontIcon(Feather.BOOK_OPEN)
    fIcon.setIconSize(64)
    fIcon.setIconColor(javafx.scene.paint.Color.web("#B0BEC5"))
    graphic = fIcon

  private val emptyState = new VBox(12):
    alignment = Pos.Center
    padding = Insets(60)

    val label = new Label("书架还是空的"):
      style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #455A64;"

    val hint = new Label("点击右下角「+」或直接将 .epub 文件拖拽到此处"):
      style = "-fx-font-size: 13px; -fx-text-fill: #90A4AE;"

    children = Seq(emptyIconView, label, hint)

  private val contentStack = new StackPane:
    alignment = Pos.Center
    children = Seq(emptyState, flowPane)

  private val scrollPane = new ScrollPane:
    content = contentStack
    fitToWidth = true
    fitToHeight = true
    hbarPolicy = ScrollPane.ScrollBarPolicy.Never
    vbarPolicy = ScrollPane.ScrollBarPolicy.AsNeeded
    style =
      "-fx-background-color: transparent; " +
      "-fx-background: #F8F9FA; " +
      "-fx-background-insets: 0; " +
      "-fx-padding: 0; " +
      "-fx-border-width: 0;"

  // 3. 右下角 Material 悬浮操作按钮 (FloatingActionButton - FAB)
  // 对齐 fragment_bookshelf.xml 中 bookshelf_addBookFab (56dp 圆形悬浮按钮)
  private val fabButton = new Button:
    prefWidth = 56
    prefHeight = 56
    minWidth = 56
    minHeight = 56
    maxWidth = 56
    maxHeight = 56

    val plusIcon = new FontIcon(Feather.PLUS)
    plusIcon.setIconSize(26)
    plusIcon.setIconColor(javafx.scene.paint.Color.WHITE)
    plusIcon.setMouseTransparent(true)
    graphic = plusIcon

    style =
      "-fx-background-color: #00A5F5; " +
      "-fx-background-radius: 28px; " +
      "-fx-border-radius: 28px; " +
      "-fx-padding: 0; " +
      "-fx-alignment: center; " +
      "-fx-cursor: hand;"

    effect = new DropShadow:
      radius = 8.0
      offsetY = 3.0
      color = Color.rgb(0, 0, 0, 0.28)

    tooltip = new Tooltip("导入图书")

    onAction = _ => openFileChooser()

  StackPane.setAlignment(fabButton, Pos.BottomRight)
  StackPane.setMargin(fabButton, Insets(0, 24, 20, 0))

  children = Seq(scrollPane, fabButton)

  // 数据绑定
  viewModel.displayedBooks.onChange { (_, _) =>
    updateBookGrid()
  }

  private def updateBookGrid(): Unit =
    flowPane.children.clear()
    val books = viewModel.displayedBooks
    if (books.isEmpty)
      emptyState.visible = true
      flowPane.visible = false
    else
      emptyState.visible = false
      flowPane.visible = true
      books.foreach { book =>
        val card = new BookCard(
          book = book,
          onOpen = b => onOpenBook(b),
          onDelete = b => confirmDelete(b)
        )
        flowPane.children.add(card)
      }

  private def openFileChooser(): Unit =
    val chooser = new FileChooser:
      title = "导入 EPUB 电子书"
      extensionFilters += new FileChooser.ExtensionFilter("EPUB Books (*.epub)", "*.epub")
    val window = this.getScene.getWindow
    val selectedFiles = chooser.showOpenMultipleDialog(window)
    if (selectedFiles != null)
      selectedFiles.foreach { file =>
        viewModel.importBookFile(file)
      }

  private def confirmDelete(book: Book): Unit =
    val alert = new Alert(Alert.AlertType.Confirmation):
      title = "删除确认"
      headerText = s"确定从书架中移除《${book.displayName}》吗？"
      contentText = "此操作仅从书架移除记录，不会删除磁盘上的原始文件。"
    val result = alert.showAndWait()
    if (result.contains(ButtonType.OK))
      viewModel.deleteBook(book)

  // 文件拖拽导入支持 (Drag & Drop)
  onDragOver = (event: DragEvent) => {
    val db = event.dragboard
    if (db.hasFiles)
      val hasEpub = db.files.exists(_.getName.toLowerCase.endsWith(".epub"))
      if (hasEpub)
        event.acceptTransferModes(TransferMode.Copy)
    event.consume()
  }

  onDragDropped = (event: DragEvent) => {
    val db = event.dragboard
    var success = false
    if (db.hasFiles)
      db.files.filter(_.getName.toLowerCase.endsWith(".epub")).foreach { file =>
        viewModel.importBookFile(file)
        success = true
      }
    event.dropCompleted = success
    event.consume()
  }

  // 初始加载
  viewModel.loadBooks()
