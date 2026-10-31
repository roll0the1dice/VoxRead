package com.roll0the1dice.voxread.desktop.ui.components

import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{Button, Label}
import scalafx.scene.layout.{HBox, Priority, Region, StackPane, VBox}
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

enum NavTab:
  case Bookshelf, Catalogs, About

/**
 * 底部导航栏，对齐 Android 原版 (activity_main.xml + bottom_nav_menu.xml):
 * - 纯净 Material 3 风格 BottomNavigationView
 * - 每个 Tab 均为原生 Button 控件，确保 100% 区域均可完美响应点击，彻底消除点击热区偏移
 * - 激活态：Material 浅蓝圆角胶囊背景高亮与主色文字
 */
class BottomNavBar(
  onTabSelected: NavTab => Unit
) extends HBox:

  alignment = Pos.Center
  prefHeight = 56
  minHeight = 56
  maxHeight = 56
  spacing = 16
  style =
    "-fx-background-color: #FFFFFF; " +
    "-fx-border-color: #E2E8F0; " +
    "-fx-border-width: 1 0 0 0;"

  private var activeTab: NavTab = NavTab.Bookshelf

  private class TabButton(val tab: NavTab, val title: String, val icon: Feather) extends Button:
    prefWidth = 140
    prefHeight = 52
    minHeight = 52
    maxHeight = 52
    cursor = scalafx.scene.Cursor.Hand

    val fontIcon = new FontIcon(icon)
    fontIcon.setIconSize(20)

    val iconPill = new StackPane:
      prefWidth = 60
      prefHeight = 28
      maxWidth = 60
      maxHeight = 28
      alignment = Pos.Center
      mouseTransparent = true
      children = Seq(new Label { graphic = fontIcon; mouseTransparent = true })

    val textLabel = new Label(title):
      mouseTransparent = true
      style = "-fx-font-size: 11px;"

    val contentBox = new VBox(2):
      alignment = Pos.Center
      mouseTransparent = true
      children = Seq(iconPill, textLabel)

    graphic = contentBox

    // 扁平原生透明按钮，支持悬停效果与全区域精准点击
    style =
      "-fx-background-color: transparent; " +
      "-fx-background-radius: 8px; " +
      "-fx-padding: 0; " +
      "-fx-border-color: transparent;"

    // 鼠标悬浮微交互
    onMouseEntered = _ => {
      if activeTab != tab then
        style = "-fx-background-color: rgba(0, 0, 0, 0.04); -fx-background-radius: 8px; -fx-padding: 0;"
    }
    onMouseExited = _ => {
      style = "-fx-background-color: transparent; -fx-background-radius: 8px; -fx-padding: 0;"
    }

    def setActive(active: Boolean): Unit =
      if active then
        iconPill.style =
          "-fx-background-color: #E0F2FE; " +
          "-fx-background-radius: 14px; " +
          "-fx-border-radius: 14px;"
        fontIcon.setIconColor(javafx.scene.paint.Color.web("#008CD0"))
        textLabel.style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #008CD0;"
      else
        iconPill.style = "-fx-background-color: transparent;"
        fontIcon.setIconColor(javafx.scene.paint.Color.web("#60656E"))
        textLabel.style = "-fx-font-size: 11px; -fx-font-weight: normal; -fx-text-fill: #60656E;"

    onAction = _ => {
      selectTab(tab)
      onTabSelected(tab)
    }

  private val tabBookshelf = new TabButton(NavTab.Bookshelf, "书架", Feather.BOOK)
  private val tabCatalogs = new TabButton(NavTab.Catalogs, "网络书库", Feather.COMPASS)
  private val tabAbout = new TabButton(NavTab.About, "关于", Feather.INFO)

  private val leftSpacer = new Region()
  private val rightSpacer = new Region()
  HBox.setHgrow(leftSpacer, Priority.Always)
  HBox.setHgrow(rightSpacer, Priority.Always)

  children = Seq(leftSpacer, tabBookshelf, tabCatalogs, tabAbout, rightSpacer)

  def selectTab(tab: NavTab): Unit =
    activeTab = tab
    tabBookshelf.setActive(tab == NavTab.Bookshelf)
    tabCatalogs.setActive(tab == NavTab.Catalogs)
    tabAbout.setActive(tab == NavTab.About)

  // 默认选中书架
  selectTab(NavTab.Bookshelf)
