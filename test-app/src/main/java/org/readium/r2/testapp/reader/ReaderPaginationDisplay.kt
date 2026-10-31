package org.readium.r2.testapp.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Page changes are immediate; an asynchronous offset must never erase them. */
internal class ReaderPaginationDisplay<Context> {
    data class Numbers(val current: Int, val total: Int?, val cumulativePending: Boolean)
    sealed interface Calculation {
        data object WaitingForIdle : Calculation
        data class Counting(val completed: Int, val total: Int) : Calculation
        data object Failed : Calculation
        data object Complete : Calculation
    }
    private data class Page<Context>(val context: Context, val index: Int, val total: Int)

    private data class BookPagination(val previousPages: Int, val totalPages: Int)
    private val bookPagination = mutableMapOf<Context, BookPagination>()
    private var page: Page<Context>? = null
    private val _numbers = MutableStateFlow<Numbers?>(null)
    val numbers = _numbers.asStateFlow()
    private val _calculation = MutableStateFlow<Calculation>(Calculation.WaitingForIdle)
    val calculation = _calculation.asStateFlow()

    fun hasPagination(context: Context): Boolean = context in bookPagination

    fun showPage(context: Context, index: Int, total: Int) {
        if (total <= 0) return
        if (page?.context != context) _calculation.value = Calculation.WaitingForIdle
        page = Page(context, index.coerceIn(0, total - 1), total)
        refresh()
    }

    fun acceptPagination(context: Context, previousPages: Int, totalPages: Int) {
        require(previousPages >= 0 && totalPages > previousPages)
        bookPagination[context] = BookPagination(previousPages, totalPages)
        if (page?.context == context) refresh()
    }

    fun setCalculation(context: Context, calculation: Calculation) {
        if (page?.context == context && !hasPagination(context)) _calculation.value = calculation
    }

    private fun refresh() {
        val current = page ?: return
        val book = bookPagination[current.context]
        if (book != null) _calculation.value = Calculation.Complete
        _numbers.value = Numbers(
            current = (book?.previousPages ?: 0) + current.index + 1,
            total = book?.totalPages,
            cumulativePending = book == null,
        )
    }
}
