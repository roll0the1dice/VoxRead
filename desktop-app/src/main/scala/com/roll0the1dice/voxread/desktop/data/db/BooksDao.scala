package com.roll0the1dice.voxread.desktop.data.db

import java.sql.Statement
import scala.util.Using
import com.roll0the1dice.voxread.desktop.data.model.{Book, Bookmark, Highlight}

class BooksDao(database: Database):

  def insertBook(book: Book): Long =
    Using.resource(database.getConnection) { conn =>
      val sql =
        s"""INSERT OR REPLACE INTO ${Book.TableName} (
           |  ${Book.CreationDate}, ${Book.Href}, ${Book.Title}, ${Book.Author},
           |  ${Book.Identifier}, ${Book.Progression}, ${Book.MediaType}, ${Book.Cover}
           |) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin

      val ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
      try
        ps.setLong(1, book.creation)
        ps.setString(2, book.href)
        ps.setString(3, book.title)
        ps.setString(4, book.author.orNull)
        ps.setString(5, book.identifier)
        ps.setString(6, book.progression.orNull)
        ps.setString(7, book.mediaType)
        ps.setString(8, book.cover.orNull)
        ps.executeUpdate()

        val rs = ps.getGeneratedKeys
        if (rs.next()) rs.getLong(1) else -1L
      finally
        ps.close()
    }

  def deleteBook(bookId: Long): Boolean =
    Using.resource(database.getConnection) { conn =>
      val ps = conn.prepareStatement(s"DELETE FROM ${Book.TableName} WHERE ${Book.Id} = ?")
      try
        ps.setLong(1, bookId)
        ps.executeUpdate() > 0
      finally
        ps.close()
    }

  def get(id: Long): Option[Book] =
    Using.resource(database.getConnection) { conn =>
      val ps = conn.prepareStatement(s"SELECT * FROM ${Book.TableName} WHERE ${Book.Id} = ?")
      try
        ps.setLong(1, id)
        val rs = ps.executeQuery()
        if (rs.next())
          Some(mapBook(rs))
        else None
      finally
        ps.close()
    }

  def getAllBooks(): List[Book] =
    Using.resource(database.getConnection) { conn =>
      val stmt = conn.createStatement()
      try
        val rs = stmt.executeQuery(s"SELECT * FROM ${Book.TableName} ORDER BY ${Book.CreationDate} DESC")
        val buffer = collection.mutable.ListBuffer[Book]()
        while (rs.next())
          buffer += mapBook(rs)
        buffer.toList
      finally
        stmt.close()
    }

  def updateCover(bookId: Long, cover: String): Int =
    Using.resource(database.getConnection) { conn =>
      val ps = conn.prepareStatement(s"UPDATE ${Book.TableName} SET ${Book.Cover} = ? WHERE ${Book.Id} = ?")
      try
        ps.setString(1, cover)
        ps.setLong(2, bookId)
        ps.executeUpdate()
      finally
        ps.close()
    }

  def updateProgression(bookId: Long, progression: String): Int =
    Using.resource(database.getConnection) { conn =>
      val ps = conn.prepareStatement(s"UPDATE ${Book.TableName} SET ${Book.Progression} = ? WHERE ${Book.Id} = ?")
      try
        ps.setString(1, progression)
        ps.setLong(2, bookId)
        ps.executeUpdate()
      finally
        ps.close()
    }

  private def mapBook(rs: java.sql.ResultSet): Book =
    Book(
      id = Some(rs.getLong(Book.Id)),
      creation = rs.getLong(Book.CreationDate),
      href = rs.getString(Book.Href),
      title = rs.getString(Book.Title),
      author = Option(rs.getString(Book.Author)),
      identifier = rs.getString(Book.Identifier),
      progression = Option(rs.getString(Book.Progression)),
      mediaType = rs.getString(Book.MediaType),
      cover = Option(rs.getString(Book.Cover))
    )
