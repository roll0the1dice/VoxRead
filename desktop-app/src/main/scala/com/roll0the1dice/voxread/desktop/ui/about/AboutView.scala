package com.roll0the1dice.voxread.desktop.ui.about

import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.{Label, ScrollPane, Separator}
import scalafx.scene.layout.{HBox, Priority, Region, VBox}
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

/**
 * 关于界面，对齐 Android 原版 fragment_about.xml:
 * - 列表式层级结构
 * - 版本信息 (应用版本、Git 分支)
 * - 版权信息与开源许可证 (BSD-3-Clause)
 * - 致谢信息
 */
class AboutView extends ScrollPane:

  fitToWidth = true
  style = "-fx-background-color: transparent; -fx-background: #F8F9FA;"

  private val container = new VBox(0):
    padding = Insets(24, 36, 40, 36)
    maxWidth = 800
    alignment = Pos.TopLeft

  private def sectionHeader(text: String): Label =
    new Label(text):
      style =
        "-fx-font-size: 16px; " +
        "-fx-font-weight: bold; " +
        "-fx-text-fill: #252A32; " +
        "-fx-padding: 24 0 10 0;"

  private def infoRow(label: String, value: String): HBox =
    val lbl = new Label(label):
      style = "-fx-font-size: 14px; -fx-text-fill: #455A64;"
    val valLbl = new Label(value):
      style = "-fx-font-size: 14px; -fx-text-fill: #60656E;"

    val spacer = new Region()
    HBox.setHgrow(spacer, Priority.Always)

    new HBox:
      padding = Insets(8, 0, 8, 0)
      alignment = Pos.CenterLeft
      children = Seq(lbl, spacer, valLbl)

  private val iconView = new Label:
    val fIcon = new FontIcon(Feather.BOOK_OPEN)
    fIcon.setIconSize(48)
    fIcon.setIconColor(javafx.scene.paint.Color.web("#00A5F5"))
    graphic = fIcon

  private val appTitle = new Label("VoxRead"):
    style = "-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #252A32;"

  private val appSub = new Label("跨平台数字阅读与公式无障碍朗读客户端"):
    style = "-fx-font-size: 13px; -fx-text-fill: #78909C;"

  private val headerBox = new VBox(6):
    alignment = Pos.Center
    padding = Insets(16, 0, 16, 0)
    children = Seq(iconView, appTitle, appSub)

  container.children = Seq(
    headerBox,
    new Separator(),

    sectionHeader("版本信息"),
    infoRow("应用版本", "1.0.0"),
    infoRow("平台架构", "Desktop (Scala 3 + ScalaFX)"),
    infoRow("Git 提交", "main"),

    sectionHeader("版权信息"),
    infoRow("版权归属", "© 2026 Readium Foundation & VoxRead Team"),
    infoRow("开源协议", "BSD 3-Clause License"),

    sectionHeader("致谢与支持"),
    infoRow("阅读核心", "Readium Architecture"),
    infoRow("语音合成", "Microsoft Edge TTS (Sec-MS-GEC v2)"),
    infoRow("公式无障碍", "MathCAT Math-to-Speech Engine")
  )

  content = container
