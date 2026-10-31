package com.roll0the1dice.voxread.desktop.data.model

case class Highlight(
  id: Option[Long] = None,
  creation: Long = System.currentTimeMillis(),
  bookId: Long,
  style: String = "highlight",
  color: Int = 0xFFFF00,
  annotation: Option[String] = None,
  location: String = "{}",
  locatorText: String = "{}"
)

object Highlight:
  val TableName = "highlights"
  val Id = "id"
  val CreationDate = "creation_date"
  val BookId = "book_id"
  val Style = "style"
  val Color = "color"
  val Annotation = "annotation"
  val Location = "location"
  val LocatorText = "locator_text"
