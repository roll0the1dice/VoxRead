package com.roll0the1dice.voxread.desktop.ui.reader

import com.roll0the1dice.voxread.desktop.data.model.ReadingProgressionData
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.data.db.BooksDao
import com.roll0the1dice.voxread.desktop.data.db.Database
import com.roll0the1dice.voxread.desktop.util.EpubContentReader
import java.io.File
import java.nio.file.Files

/** Dependency-free regression checks; run this entry point after compileTestKotlin. */
object ReadingPositionRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        val chapters = listOf("part-one/chapter.xhtml", "part-two/chapter.xhtml", "end.xhtml")
        check(resolveSavedChapterIndex(chapters, "part-two/chapter.xhtml", 0) == 1)
        check(resolveSavedChapterIndex(chapters, "/part-two/chapter.xhtml#paragraph", 0) == 1)
        check(resolveSavedChapterIndex(chapters, "chapter.xhtml", 1) == 1)
        check(resolveSavedChapterIndex(chapters, "old/end.xhtml", 0) == 2)
        check(resolveSavedChapterIndex(chapters, "missing.xhtml", 99) == 2)
        check(resolveSavedChapterIndex(chapters, "", -1) == 0)
        check(resolveSavedChapterIndex(emptyList(), "", 99) == 0)
        // ZIP entry names are case sensitive, even when their basenames match.
        check(resolveSavedChapterIndex(listOf("A.xhtml", "a.xhtml"), "a.xhtml", 0) == 1)
        val firstBlock = ReadingProgressionData(blockIndex = 0, scrollOffset = 42, utteranceIndex = 8)
        check(ReadingProgressionData.fromJsonString(firstBlock.toJsonString()) == firstBlock)
        check(ReadingProgressionData.fromJsonString("0.5")?.hasBlockPosition == false)
        check(ReadingProgressionData.fromJsonString("""{"locations":{"progression":0.5}}""")?.hasBlockPosition == false)
        println("Reading position regression checks passed (11 cases).")
        args.firstOrNull()?.let { verifyBookPositions(File(it)) }
    }

    private fun verifyBookPositions(epub: File) {
        val chapters = EpubContentReader(epub).chapters
        check(chapters.isNotEmpty())
        val dbFile = Files.createTempFile("voxread-position-regression-", ".db").toFile()
        try {
            val dao = BooksDao(Database(dbFile.path).apply { init() })
            val id = dao.insertBook(Book(href = epub.path, title = "Position regression"))
            // Use the actual spine and persistence path, without changing the user's database.
            chapters.forEachIndexed { index, chapter ->
                val position = ReadingProgressionData(
                    chapterIndex = index, chapterHref = chapter.href,
                    chapterTitle = chapter.title, blockIndex = index, scrollOffset = 42
                )
                check(dao.updateProgression(id, position.toJsonString()) == 1)
                val restored = checkNotNull(dao.get(id)?.parsedProgression)
                check(restored == position)
                check(resolveSavedChapterIndex(chapters.map { it.href }, restored.chapterHref, restored.chapterIndex) == index)
            }
            println("EPUB persistence round trips passed (${chapters.size} spine items).")
        } finally {
            dbFile.delete()
        }
    }
}
