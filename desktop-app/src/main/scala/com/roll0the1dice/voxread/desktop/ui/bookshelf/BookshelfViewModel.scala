package com.roll0the1dice.voxread.desktop.ui.bookshelf

import java.io.File
import scalafx.application.Platform
import scalafx.collections.ObservableBuffer
import scalafx.beans.property.StringProperty
import com.roll0the1dice.voxread.desktop.data.db.{BooksDao, Database}
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.util.EpubParser

class BookshelfViewModel(dao: BooksDao):

  val allBooks = ObservableBuffer[Book]()
  val displayedBooks = ObservableBuffer[Book]()
  val searchQuery = StringProperty("")

  searchQuery.onChange { (_, _, query) =>
    filterBooks(query)
  }

  def loadBooks(): Unit =
    val list = dao.getAllBooks()
    Platform.runLater:
      allBooks.clear()
      allBooks ++= list
      filterBooks(searchQuery.value)

  def importBookFile(file: File): Unit =
    if (file.exists() && file.getName.toLowerCase.endsWith(".epub"))
      val parsedBook = EpubParser.importEpubFile(file)
      val newId = dao.insertBook(parsedBook)
      val finalBook = parsedBook.copy(id = Some(newId))
      Platform.runLater:
        allBooks.prepend(finalBook)
        filterBooks(searchQuery.value)

  def deleteBook(book: Book): Unit =
    book.id.foreach { id =>
      dao.deleteBook(id)
      Platform.runLater:
        allBooks.removeIf(_.id.contains(id))
        filterBooks(searchQuery.value)
    }

  private def filterBooks(query: String): Unit =
    val q = Option(query).map(_.trim.toLowerCase).getOrElse("")
    displayedBooks.clear()
    if (q.isEmpty)
      displayedBooks ++= allBooks
    else
      displayedBooks ++= allBooks.filter { b =>
        b.title.toLowerCase.contains(q) || b.author.exists(_.toLowerCase.contains(q))
      }
