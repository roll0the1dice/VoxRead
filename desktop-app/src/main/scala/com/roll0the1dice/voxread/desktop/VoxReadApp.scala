package com.roll0the1dice.voxread.desktop

import javafx.application.Application
import scalafx.Includes._
import scalafx.application.JFXApp3
import scalafx.scene.Scene
import scalafx.scene.Node
import scalafx.scene.layout.{BorderPane, StackPane}
import atlantafx.base.theme.PrimerLight
import com.roll0the1dice.voxread.desktop.data.db.{BooksDao, Database}
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.ui.about.AboutView
import com.roll0the1dice.voxread.desktop.ui.bookshelf.{BookshelfView, BookshelfViewModel}
import com.roll0the1dice.voxread.desktop.ui.catalogs.CatalogsView
import com.roll0the1dice.voxread.desktop.ui.components.{BottomNavBar, NavTab}
import com.roll0the1dice.voxread.desktop.ui.reader.ReaderView

/**
 * 桌面客户端主程序，对齐 Android 原版架构 (MainActivity.kt + activity_main.xml):
 * - 纯净 Material 规范主框架
 * - 底部 BottomNavigationView 三标签切换 (书架、网络书库、关于)
 * - 打开书籍时以全屏 Reader 容器沉浸式接管 (类似 ReaderActivity)
 */
object VoxReadApp extends JFXApp3:

  override def start(): Unit =
    // 初始化基础样式
    Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet)

    // 数据库与 DAO
    val db = Database.default
    val booksDao = new BooksDao(db)

    // ViewModel
    val bookshelfViewModel = new BookshelfViewModel(booksDao)

    // 主窗口根布局
    val rootPane = new BorderPane:
      style = "-fx-background-color: #F8F9FA;"

    // 主内容容器 (对齐 nav_host_fragment)
    val contentStack = new StackPane:
      style = "-fx-background-color: #F8F9FA;"

    var currentBottomBar: Option[BottomNavBar] = None

    // 打开阅读器 (对齐 Android ReaderActivity 的全屏接管机制)
    def openBook(book: Book): Unit =
      lazy val reader: ReaderView = new ReaderView(
        book = book,
        onClose = () => {
          rootPane.center = contentStack
          rootPane.bottom = currentBottomBar.orNull
        }
      )
      rootPane.bottom = null
      rootPane.center = reader

    // 页面实例
    lazy val bookshelfView = new BookshelfView(
      viewModel = bookshelfViewModel,
      onOpenBook = openBook
    )
    lazy val catalogsView = new CatalogsView()
    lazy val aboutView = new AboutView()

    def showMainTab(tab: NavTab): Unit =
      val view: Node = tab match
        case NavTab.Bookshelf => bookshelfView
        case NavTab.Catalogs  => catalogsView
        case NavTab.About     => aboutView
      contentStack.children = Seq(view)

    // 底部导航栏 (对齐 BottomNavigationView)
    val bottomNavBar = new BottomNavBar(onTabSelected = tab => showMainTab(tab))
    currentBottomBar = Some(bottomNavBar)

    rootPane.center = contentStack
    rootPane.bottom = bottomNavBar

    // 默认展示书架
    showMainTab(NavTab.Bookshelf)

    stage = new JFXApp3.PrimaryStage:
      title = "VoxRead"
      scene = new Scene(rootPane, 1050, 720)
      minWidth = 800
      minHeight = 550
