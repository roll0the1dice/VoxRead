package com.roll0the1dice.voxread.desktop.ui.catalogs

import scalafx.Includes._
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.control.Label
import scalafx.scene.layout.VBox
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

/**
 * 在线书库视图，对齐 Android 原版 fragment_catalog.xml
 */
class CatalogsView extends VBox:

  alignment = Pos.Center
  spacing = 12
  padding = Insets(60)
  style = "-fx-background-color: #F8F9FA;"

  private val iconView = new Label:
    val fIcon = new FontIcon(Feather.COMPASS)
    fIcon.setIconSize(56)
    fIcon.setIconColor(javafx.scene.paint.Color.web("#B0BEC5"))
    graphic = fIcon

  private val titleLabel = new Label("网络书库 (OPDS)"):
    style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #455A64;"

  private val hintLabel = new Label("支持配置和浏览 OPDS 1.x / 2.0 在线数字图书库，功能即将上线"):
    style = "-fx-font-size: 13px; -fx-text-fill: #90A4AE;"

  children = Seq(iconView, titleLabel, hintLabel)
