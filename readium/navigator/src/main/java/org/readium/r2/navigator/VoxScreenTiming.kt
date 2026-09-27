package org.readium.r2.navigator

import android.os.SystemClock
import android.util.Log

/**
 * Times the first screen of text, from the book tap until that text is drawn.
 * [t] is from the tap. [screen] is from [ReaderActivity] creation.
 */
public object VoxScreenTiming {
    private var tapAt = 0L
    private var screenAt = 0L
    private val seen = LinkedHashSet<String>()
    private val counts = HashMap<String, Int>()

    /**
     * Control for the first-screen experiment. When true, the chapter keeps
     * original MathML and does not wrap, classify, or remeasure formulas.
     */
    public var skipFormulaEnhance: Boolean = false

    @Synchronized
    public fun begin() {
        tapAt = SystemClock.elapsedRealtime()
        screenAt = 0L
        seen.clear()
        counts.clear()
        log("tap", "")
    }

    @Synchronized
    public fun mark(stage: String, extra: String = "") {
        val start = tapAt
        if (start == 0L || !seen.add(stage)) return
        if (stage == "screen") screenAt = SystemClock.elapsedRealtime()
        log(stage, extra)
    }

    /** Logs every occurrence, including repeats such as remeasure and visual callbacks. */
    @Synchronized
    public fun note(stage: String, extra: String = "") {
        if (tapAt == 0L) return
        val count = (counts[stage] ?: 0) + 1
        counts[stage] = count
        val suffix = " n=$count" + if (extra.isBlank()) "" else " $extra"
        log(stage, suffix)
    }

    private fun log(stage: String, extra: String) {
        val now = SystemClock.elapsedRealtime()
        val screen = screenAt
        val fromScreen = if (screen > 0L) " screen=${now - screen}ms" else ""
        val detail = if (extra.isBlank()) "" else if (extra.startsWith(" ")) extra else " $extra"
        Log.i(TAG, "stage=$stage t=${now - tapAt}ms$fromScreen$detail")
    }

    private const val TAG = "VoxTextTiming"
}
