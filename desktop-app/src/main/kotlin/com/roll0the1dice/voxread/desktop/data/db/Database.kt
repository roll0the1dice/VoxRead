package com.roll0the1dice.voxread.desktop.data.db

import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.data.model.Bookmark
import com.roll0the1dice.voxread.desktop.data.model.Highlight
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class Database(private val dbPath: String) {

    init {
        Class.forName("org.sqlite.JDBC")
    }

    fun getConnection(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    fun init() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS ${Book.TABLE_NAME} (
                      ${Book.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                      ${Book.CREATION_DATE} INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                      ${Book.HREF} TEXT NOT NULL,
                      ${Book.TITLE} TEXT,
                      ${Book.AUTHOR} TEXT,
                      ${Book.IDENTIFIER} TEXT NOT NULL,
                      ${Book.PROGRESSION} TEXT,
                      ${Book.MEDIA_TYPE} TEXT NOT NULL,
                      ${Book.COVER} TEXT
                    );
                    """.trimIndent()
                )

                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS ${Bookmark.TABLE_NAME} (
                      ${Bookmark.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                      ${Bookmark.CREATION_DATE} INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                      ${Bookmark.BOOK_ID} INTEGER NOT NULL,
                      ${Bookmark.RESOURCE_INDEX} INTEGER NOT NULL,
                      ${Bookmark.RESOURCE_HREF} TEXT NOT NULL,
                      ${Bookmark.RESOURCE_TYPE} TEXT NOT NULL,
                      ${Bookmark.RESOURCE_TITLE} TEXT NOT NULL,
                      ${Bookmark.LOCATION} TEXT NOT NULL,
                      ${Bookmark.LOCATOR_TEXT} TEXT NOT NULL,
                      UNIQUE(${Bookmark.BOOK_ID}, ${Bookmark.LOCATION})
                    );
                    """.trimIndent()
                )

                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS ${Highlight.TABLE_NAME} (
                      ${Highlight.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                      ${Highlight.CREATION_DATE} INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                      ${Highlight.BOOK_ID} INTEGER NOT NULL,
                      ${Highlight.STYLE} TEXT NOT NULL,
                      ${Highlight.COLOR} INTEGER NOT NULL,
                      ${Highlight.ANNOTATION} TEXT,
                      ${Highlight.LOCATION} TEXT NOT NULL,
                      ${Highlight.LOCATOR_TEXT} TEXT NOT NULL
                    );
                    """.trimIndent()
                )
            }
        }
    }

    companion object {
        val defaultDataDir: File by lazy {
            val userHome = System.getProperty("user.home", ".")
            val os = System.getProperty("os.name", "").lowercase()
            val dir = when {
                os.contains("win") -> {
                    val appData = System.getenv("APPDATA") ?: userHome
                    File(appData, "VoxRead")
                }
                os.contains("mac") -> File(userHome, "Library/Application Support/VoxRead")
                else -> File(userHome, ".voxread")
            }
            if (!dir.exists()) dir.mkdirs()
            dir
        }

        val defaultDbFile: File by lazy { File(defaultDataDir, "voxread.db") }
        val coversDir: File by lazy {
            val dir = File(defaultDataDir, "covers")
            if (!dir.exists()) dir.mkdirs()
            dir
        }

        val default: Database by lazy {
            Database(defaultDbFile.absolutePath).apply { init() }
        }
    }
}
