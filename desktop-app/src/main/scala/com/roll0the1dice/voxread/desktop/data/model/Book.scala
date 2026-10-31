package com.roll0the1dice.voxread.desktop.data.model

import java.io.File

case class Book(
  id: Option[Long] = None,
  creation: Long = System.currentTimeMillis(),
  href: String,
  title: String,
  author: Option[String] = None,
  identifier: String = "",
  progression: Option[String] = None,
  mediaType: String = "application/epub+zip",
  cover: Option[String] = None
):
  def file: File = new File(href)
  def exists: Boolean = file.exists()
  def displayName: String = if (title.trim.nonEmpty) title else file.getName

object Book:
  val TableName = "books"
  val Id = "id"
  val CreationDate = "creation_date"
  val Href = "href"
  val Title = "title"
  val Author = "author"
  val Identifier = "identifier"
  val Progression = "progression"
  val MediaType = "media_type"
  val Cover = "cover"
