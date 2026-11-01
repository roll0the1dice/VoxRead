package com.roll0the1dice.voxread.desktop.data.db

import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.data.model.Bookmark
import com.roll0the1dice.voxread.desktop.data.model.Highlight
import java.sql.ResultSet
import java.sql.Statement

class BooksDao(private val database: Database) {

    fun insertBook(book: Book): Long {
        val existing = findByHref(book.href)
        if (existing?.id != null) {
            updateBookMetadata(existing.id, book)
            return existing.id
        }

        return database.getConnection().use { conn ->
            val sql = """
                INSERT OR REPLACE INTO ${Book.TABLE_NAME} (
                  ${Book.CREATION_DATE}, ${Book.HREF}, ${Book.TITLE}, ${Book.AUTHOR},
                  ${Book.IDENTIFIER}, ${Book.PROGRESSION}, ${Book.MEDIA_TYPE}, ${Book.COVER}
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setLong(1, book.creation)
                ps.setString(2, book.href)
                ps.setString(3, book.title)
                ps.setString(4, book.author)
                ps.setString(5, book.identifier)
                ps.setString(6, book.progression)
                ps.setString(7, book.mediaType)
                ps.setString(8, book.cover)
                ps.executeUpdate()

                val rs = ps.generatedKeys
                if (rs.next()) rs.getLong(1) else -1L
            }
        }
    }

    fun deleteBook(bookId: Long): Boolean {
        return database.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM ${Bookmark.TABLE_NAME} WHERE ${Bookmark.BOOK_ID} = ?").use { ps ->
                    ps.setLong(1, bookId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM ${Highlight.TABLE_NAME} WHERE ${Highlight.BOOK_ID} = ?").use { ps ->
                    ps.setLong(1, bookId)
                    ps.executeUpdate()
                }
                val affected = conn.prepareStatement("DELETE FROM ${Book.TABLE_NAME} WHERE ${Book.ID} = ?").use { ps ->
                    ps.setLong(1, bookId)
                    ps.executeUpdate()
                }
                conn.commit()
                affected > 0
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun deleteBookByHref(href: String): Boolean {
        val book = findByHref(href)
        return if (book?.id != null) {
            deleteBook(book.id)
        } else {
            database.getConnection().use { conn ->
                conn.prepareStatement("DELETE FROM ${Book.TABLE_NAME} WHERE ${Book.HREF} = ?").use { ps ->
                    ps.setString(1, href)
                    ps.executeUpdate() > 0
                }
            }
        }
    }

    fun findByHref(href: String): Book? {
        return database.getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM ${Book.TABLE_NAME} WHERE ${Book.HREF} = ?").use { ps ->
                ps.setString(1, href)
                val rs = ps.executeQuery()
                if (rs.next()) mapBook(rs) else null
            }
        }
    }

    fun updateBookMetadata(bookId: Long, book: Book): Int {
        return database.getConnection().use { conn ->
            val sql = """
                UPDATE ${Book.TABLE_NAME}
                SET ${Book.TITLE} = ?, ${Book.AUTHOR} = ?, ${Book.IDENTIFIER} = ?,
                    ${Book.MEDIA_TYPE} = ?, ${Book.COVER} = COALESCE(?, ${Book.COVER})
                WHERE ${Book.ID} = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, book.title)
                ps.setString(2, book.author)
                ps.setString(3, book.identifier)
                ps.setString(4, book.mediaType)
                ps.setString(5, book.cover)
                ps.setLong(6, bookId)
                ps.executeUpdate()
            }
        }
    }

    fun get(id: Long): Book? {
        return database.getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM ${Book.TABLE_NAME} WHERE ${Book.ID} = ?").use { ps ->
                ps.setLong(1, id)
                val rs = ps.executeQuery()
                if (rs.next()) mapBook(rs) else null
            }
        }
    }

    fun getAllBooks(): List<Book> {
        return database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM ${Book.TABLE_NAME} ORDER BY ${Book.CREATION_DATE} DESC")
                val list = mutableListOf<Book>()
                while (rs.next()) {
                    list.add(mapBook(rs))
                }
                list
            }
        }
    }

    fun updateCover(bookId: Long, cover: String): Int {
        return database.getConnection().use { conn ->
            conn.prepareStatement("UPDATE ${Book.TABLE_NAME} SET ${Book.COVER} = ? WHERE ${Book.ID} = ?").use { ps ->
                ps.setString(1, cover)
                ps.setLong(2, bookId)
                ps.executeUpdate()
            }
        }
    }

    fun updateProgression(bookId: Long, progression: String): Int {
        return database.getConnection().use { conn ->
            conn.prepareStatement("UPDATE ${Book.TABLE_NAME} SET ${Book.PROGRESSION} = ? WHERE ${Book.ID} = ?").use { ps ->
                ps.setString(1, progression)
                ps.setLong(2, bookId)
                ps.executeUpdate()
            }
        }
    }

    private fun mapBook(rs: ResultSet): Book {
        return Book(
            id = rs.getLong(Book.ID),
            creation = rs.getLong(Book.CREATION_DATE),
            href = rs.getString(Book.HREF),
            title = rs.getString(Book.TITLE) ?: "",
            author = rs.getString(Book.AUTHOR),
            identifier = rs.getString(Book.IDENTIFIER) ?: "",
            progression = rs.getString(Book.PROGRESSION),
            mediaType = rs.getString(Book.MEDIA_TYPE) ?: "application/epub+zip",
            cover = rs.getString(Book.COVER)
        )
    }
}
