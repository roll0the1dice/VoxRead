package com.roll0the1dice.voxread.desktop.data.model

case class Bookmark(
  id: Option[Long] = None,
  creation: Long = System.currentTimeMillis(),
  bookId: Long,
  resourceIndex: Long = 0L,
  resourceHref: String = "",
  resourceType: String = "",
  resourceTitle: String = "",
  location: String = "{}",
  locatorText: String = "{}"
)

object Bookmark:
  val TableName = "bookmarks"
  val Id = "id"
  val CreationDate = "creation_date"
  val BookId = "book_id"
  val ResourceIndex = "resource_index"
  val ResourceHref = "resource_href"
  val ResourceType = "resource_type"
  val ResourceTitle = "resource_title"
  val Location = "location"
  val LocatorText = "locator_text"
