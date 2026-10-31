package org.readium.r2.testapp.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPaginationDisplayTest {
    @Test
    fun `denominator remains the complete book total when changing chapters`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-1", 5, 10)
        display.acceptPagination("chapter-1", 0, 100)
        assertEquals(ReaderPaginationDisplay.Numbers(6, 100, false), display.numbers.value)
        display.showPage("chapter-2", 1, 6)
        display.acceptPagination("chapter-2", 10, 100)
        assertEquals(ReaderPaginationDisplay.Numbers(12, 100, false), display.numbers.value)
    }

    @Test
    fun `the final page of the last chapter equals the complete book total`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("last-chapter", 4, 5)
        display.acceptPagination("last-chapter", 95, 100)
        assertEquals(ReaderPaginationDisplay.Numbers(100, 100, false), display.numbers.value)
    }

    @Test
    fun `automatic page changes preserve ongoing background calculation progress`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-2", 1, 6)
        display.setCalculation("chapter-2", ReaderPaginationDisplay.Calculation.Counting(1, 2))
        display.showPage("chapter-2", 2, 6)
        assertEquals(ReaderPaginationDisplay.Calculation.Counting(1, 2), display.calculation.value)
        assertEquals(ReaderPaginationDisplay.Numbers(3, null, true), display.numbers.value)
    }

    @Test
    fun `counting reports progress and switches to the cumulative result`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-2", 1, 6)
        display.setCalculation("chapter-2", ReaderPaginationDisplay.Calculation.WaitingForIdle)
        display.setCalculation("chapter-2", ReaderPaginationDisplay.Calculation.Counting(1, 2))
        assertEquals(ReaderPaginationDisplay.Calculation.Counting(1, 2), display.calculation.value)
        display.acceptPagination("chapter-2", 18, 100)
        assertEquals(ReaderPaginationDisplay.Calculation.Complete, display.calculation.value)
        assertEquals(ReaderPaginationDisplay.Numbers(20, 100, false), display.numbers.value)
    }

    @Test
    fun `failure is visible while preserving page numbers and supports retry`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-2", 1, 6)
        display.setCalculation("chapter-2", ReaderPaginationDisplay.Calculation.Failed)
        assertEquals(ReaderPaginationDisplay.Calculation.Failed, display.calculation.value)
        assertEquals(ReaderPaginationDisplay.Numbers(2, null, true), display.numbers.value)
        display.setCalculation("chapter-2", ReaderPaginationDisplay.Calculation.Counting(0, 2))
        display.acceptPagination("chapter-2", 18, 100)
        display.setCalculation("chapter-2", ReaderPaginationDisplay.Calculation.Failed)
        assertEquals(ReaderPaginationDisplay.Calculation.Complete, display.calculation.value)
    }

    @Test
    fun `an old chapter cannot change the current calculation status`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-1", 0, 10)
        display.showPage("chapter-2", 1, 6)
        display.setCalculation("chapter-2", ReaderPaginationDisplay.Calculation.Counting(1, 2))
        display.setCalculation("chapter-1", ReaderPaginationDisplay.Calculation.Failed)
        assertEquals(ReaderPaginationDisplay.Calculation.Counting(1, 2), display.calculation.value)
    }

    @Test
    fun `page numbers remain visible when counting is cancelled for user input`() = runBlocking {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-2", 1, 6)
        val pendingOffset = CompletableDeferred<Int>()
        val counting = launch(start = CoroutineStart.UNDISPATCHED) {
            display.acceptPagination("chapter-2", pendingOffset.await(), 100)
        }
        counting.cancel()
        counting.join()
        assertEquals(ReaderPaginationDisplay.Numbers(2, null, true), display.numbers.value)
        display.showPage("chapter-2", 2, 6)
        assertEquals(ReaderPaginationDisplay.Numbers(3, null, true), display.numbers.value)
    }

    @Test
    fun `late offset applies to the latest page instead of the original request`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-2", 1, 6)
        display.showPage("chapter-2", 3, 6)
        display.acceptPagination("chapter-2", 18, 100)
        assertEquals(ReaderPaginationDisplay.Numbers(22, 100, false), display.numbers.value)
    }

    @Test
    fun `cached offset updates subsequent page turns immediately`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-2", 1, 6)
        display.acceptPagination("chapter-2", 18, 100)
        display.showPage("chapter-2", 2, 6)
        assertEquals(ReaderPaginationDisplay.Numbers(21, 100, false), display.numbers.value)
    }

    @Test
    fun `a stale chapter result cannot replace the visible chapter`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-1", 0, 10)
        display.showPage("chapter-2", 1, 6)
        display.acceptPagination("chapter-1", 0, 100)
        assertEquals(ReaderPaginationDisplay.Numbers(2, null, true), display.numbers.value)
    }

    @Test
    fun `changing layout does not reuse the previous layout offset`() {
        val display = ReaderPaginationDisplay<String>()
        display.showPage("chapter-2-small-font", 1, 6)
        display.acceptPagination("chapter-2-small-font", 18, 100)
        display.showPage("chapter-2-large-font", 2, 9)
        assertEquals(ReaderPaginationDisplay.Numbers(3, null, true), display.numbers.value)
    }
}
