package com.roll0the1dice.voxread.desktop.data.db

import java.io.File
import java.sql.{Connection, DriverManager}
import com.roll0the1dice.voxread.desktop.data.model.{Book, Bookmark, Highlight}

class Database(dbPath: String):

  Class.forName("org.sqlite.JDBC")

  def getConnection: Connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")

  def init(): Unit =
    val conn = getConnection
    try
      val stmt = conn.createStatement()
      try
        // Books table
        stmt.executeUpdate(
          s"""CREATE TABLE IF NOT EXISTS ${Book.TableName} (
             |  ${Book.Id} INTEGER PRIMARY KEY AUTOINCREMENT,
             |  ${Book.CreationDate} INTEGER DEFAULT (strftime('%s', 'now') * 1000),
             |  ${Book.Href} TEXT NOT NULL,
             |  ${Book.Title} TEXT,
             |  ${Book.Author} TEXT,
             |  ${Book.Identifier} TEXT NOT NULL,
             |  ${Book.Progression} TEXT,
             |  ${Book.MediaType} TEXT NOT NULL,
             |  ${Book.Cover} TEXT
             |);""".stripMargin
        )

        // Bookmarks table
        stmt.executeUpdate(
          s"""CREATE TABLE IF NOT EXISTS ${Bookmark.TableName} (
             |  ${Bookmark.Id} INTEGER PRIMARY KEY AUTOINCREMENT,
             |  ${Bookmark.CreationDate} INTEGER DEFAULT (strftime('%s', 'now') * 1000),
             |  ${Bookmark.BookId} INTEGER NOT NULL,
             |  ${Bookmark.ResourceIndex} INTEGER NOT NULL,
             |  ${Bookmark.ResourceHref} TEXT NOT NULL,
             |  ${Bookmark.ResourceType} TEXT NOT NULL,
             |  ${Bookmark.ResourceTitle} TEXT NOT NULL,
             |  ${Bookmark.Location} TEXT NOT NULL,
             |  ${Bookmark.LocatorText} TEXT NOT NULL,
             |  UNIQUE(${Bookmark.BookId}, ${Bookmark.Location})
             |);""".stripMargin
        )

        // Highlights table
        stmt.executeUpdate(
          s"""CREATE TABLE IF NOT EXISTS ${Highlight.TableName} (
             |  ${Highlight.Id} INTEGER PRIMARY KEY AUTOINCREMENT,
             |  ${Highlight.CreationDate} INTEGER DEFAULT (strftime('%s', 'now') * 1000),
             |  ${Highlight.BookId} INTEGER NOT NULL,
             |  ${Highlight.Style} TEXT NOT NULL,
             |  ${Highlight.Color} INTEGER NOT NULL,
             |  ${Highlight.Annotation} TEXT,
             |  ${Highlight.Location} TEXT NOT NULL,
             |  ${Highlight.LocatorText} TEXT NOT NULL
             |);""".stripMargin
        )
      finally
        stmt.close()
    finally
      conn.close()

object Database:
  lazy val defaultDataDir: File =
    val userHome = System.getProperty("user.home", ".")
    val os = System.getProperty("os.name", "").toLowerCase
    val dir = if (os.contains("win"))
      val appData = Option(System.getenv("APPDATA")).getOrElse(userHome)
      new File(appData, "VoxRead")
    else if (os.contains("mac"))
      new File(userHome, "Library/Application Support/VoxRead")
    else
      new File(userHome, ".voxread")
    if (!dir.exists()) dir.mkdirs()
    dir

  lazy val defaultDbFile: File = new File(defaultDataDir, "voxread.db")
  lazy val coversDir: File =
    val dir = new File(defaultDataDir, "covers")
    if (!dir.exists()) dir.mkdirs()
    dir

  lazy val default: Database =
    val db = new Database(defaultDbFile.getAbsolutePath)
    db.init()
    db
