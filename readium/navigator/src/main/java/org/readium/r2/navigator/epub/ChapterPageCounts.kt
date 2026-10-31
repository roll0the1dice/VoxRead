package org.readium.r2.navigator.epub

/** Actual screen-page counts for one layout, indexed by reading order. */
internal class ChapterPageCounts {
    data class Totals(val pagesBefore: Int, val totalPages: Int)
    private val counts = mutableMapOf<Int, Int>()

    @Synchronized
    fun record(chapter: Int, pages: Int) {
        require(chapter >= 0 && pages > 0)
        counts[chapter] = pages
    }

    @Synchronized
    fun missingBefore(chapter: Int): List<Int> =
        (0 until chapter).filter { it !in counts }

    /** A missing chapter must never be silently counted as zero pages. */
    @Synchronized
    fun pagesBefore(chapter: Int): Int? {
        var total = 0
        for (index in 0 until chapter) {
            total += counts[index] ?: return null
        }
        return total
    }

    /** Reduce by reading-order index; map completion order never changes page numbering. */
    @Synchronized
    fun reduce(currentChapter: Int, chapterCount: Int): Totals? {
        require(currentChapter in 0 until chapterCount)
        var previous = 0
        var total = 0
        for (chapter in 0 until chapterCount) {
            val pages = counts[chapter] ?: return null
            if (chapter < currentChapter) previous += pages
            total += pages
        }
        return Totals(previous, total)
    }

    @Synchronized
    fun clear() = counts.clear()
}
