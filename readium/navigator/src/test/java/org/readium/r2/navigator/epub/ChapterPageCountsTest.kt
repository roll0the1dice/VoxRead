package org.readium.r2.navigator.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterPageCountsTest {
    @Test
    fun `reduce calculates book total and prefix from unordered map results`() {
        val counts = ChapterPageCounts()
        counts.record(3, 20)
        counts.record(1, 8)
        counts.record(2, 6)
        counts.record(0, 10)
        assertEquals(ChapterPageCounts.Totals(18, 44), counts.reduce(2, 4))
        assertEquals(ChapterPageCounts.Totals(0, 44), counts.reduce(0, 4))
        assertEquals(ChapterPageCounts.Totals(24, 44), counts.reduce(3, 4))
    }

    @Test
    fun `reduce does not return a partial denominator when a later chapter is missing`() {
        val counts = ChapterPageCounts()
        counts.record(0, 10)
        counts.record(1, 8)
        assertNull(counts.reduce(0, 3))
    }

    @Test
    fun `first chapter has no offset`() {
        assertEquals(0, ChapterPageCounts().pagesBefore(0))
    }

    @Test
    fun `book total includes chapters after the current one`() {
        val counts = ChapterPageCounts()
        counts.record(0, 10)
        counts.record(1, 8)
        counts.record(2, 6)
        counts.record(3, 20)
        val offset = requireNotNull(counts.pagesBefore(2))
        assertEquals(20, offset + 2)
        assertEquals(44, counts.pagesBefore(4))
    }

    @Test
    fun `revisiting chapters replaces counts instead of adding twice`() {
        val counts = ChapterPageCounts()
        counts.record(0, 10)
        counts.record(1, 8)
        counts.record(0, 10)
        assertEquals(18, counts.pagesBefore(2))
        assertEquals(10, counts.pagesBefore(1))
    }

    @Test
    fun `skipped chapters must be counted before returning an offset`() {
        val counts = ChapterPageCounts()
        counts.record(0, 10)
        counts.record(2, 6)
        assertNull(counts.pagesBefore(3))
        assertEquals(listOf(1), counts.missingBefore(3))
        counts.record(1, 8)
        assertEquals(24, counts.pagesBefore(3))
    }

    @Test
    fun `a changed layout discards old page counts`() {
        val counts = ChapterPageCounts()
        counts.record(0, 10)
        counts.clear()
        assertNull(counts.pagesBefore(1))
        counts.record(0, 15)
        assertEquals(15, counts.pagesBefore(1))
    }
}
