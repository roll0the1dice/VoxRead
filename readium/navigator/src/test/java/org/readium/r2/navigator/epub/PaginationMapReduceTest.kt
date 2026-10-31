package org.readium.r2.navigator.epub

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationMapReduceTest {
    @Test
    fun `foreground navigation cancels maps without cancelling the caller and supports resuming`() = runBlocking {
        val mapper = PaginationMapReduce(2)
        val started = AtomicInteger()
        val active = AtomicInteger()
        val mapsStarted = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        val caller = launch {
            try {
                mapper.map(
                    chapters = (0 until 20).toList(),
                    measure = { _, _ ->
                        active.incrementAndGet()
                        if (started.incrementAndGet() == 2) mapsStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                    onMapped = { _, _ -> error("Cancelled maps cannot publish") },
                )
                error("Navigation must cancel the active map scope")
            } catch (_: CancellationException) {
                assertTrue(currentCoroutineContext().isActive)
            }
            mapper.map(listOf(0), { _, _ -> 17 }, { _, pages -> published += pages })
        }
        withTimeout(3000) { mapsStarted.await() }
        mapper.cancel()
        caller.join()
        assertEquals(0, active.get())
        assertEquals(listOf(17), published)
        assertTrue(!caller.isCancelled)
    }

    @Test
    fun `default map concurrency uses eight distinct slots and queues remaining chapters`() = runBlocking {
        val counts = ChapterPageCounts()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = AtomicInteger()
        val eightStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slots = mutableSetOf<Int>()
        val job = launch {
            PaginationMapReduce().map(
                chapters = (0 until 24).toList(),
                measure = { _, slot ->
                    slots += slot
                    val concurrent = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrent) }
                    if (started.incrementAndGet() == 8) eightStarted.complete(Unit)
                    try {
                        release.await()
                        delay(1)
                        1
                    } finally {
                        active.decrementAndGet()
                    }
                },
                onMapped = { chapter, pages -> counts.record(chapter, pages) },
            )
        }
        withTimeout(3000) { eightStarted.await() }
        assertEquals(8, active.get())
        assertEquals(8, started.get())
        assertEquals((0 until 8).toSet(), slots)
        release.complete(Unit)
        job.join()
        assertEquals(8, maximum.get())
        assertEquals(ChapterPageCounts.Totals(12, 24), counts.reduce(12, 24))
    }

    @Test
    fun `maps overlap with bounded concurrency and reduce independently of completion order`() = runBlocking {
        val counts = ChapterPageCounts()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = AtomicInteger()
        val firstPair = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completionOrder = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            PaginationMapReduce(2).map(
                chapters = listOf(0, 1, 2, 3),
                measure = { chapter, _ ->
                    val concurrent = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrent) }
                    if (started.incrementAndGet() == 2) firstPair.complete(Unit)
                    try {
                        release.await()
                        delay(if (chapter == 0) 50 else 1)
                        (chapter + 1) * 10
                    } finally {
                        active.decrementAndGet()
                    }
                },
                onMapped = { chapter, pages ->
                    completionOrder += chapter
                    counts.record(chapter, pages)
                },
            )
        }
        withTimeout(3000) { firstPair.await() }
        assertEquals(2, started.get())
        assertEquals(2, active.get())
        release.complete(Unit)
        job.join()
        assertEquals(2, maximum.get())
        assertEquals(1, completionOrder.first())
        assertEquals(ChapterPageCounts.Totals(30, 100), counts.reduce(2, 4))
    }

    @Test
    fun `failed maps retain completed chapters and retry only missing chapters`() = runBlocking {
        val counts = ChapterPageCounts()
        val mapReduce = PaginationMapReduce(2)
        mapReduce.map(
            listOf(0, 1, 2),
            measure = { chapter, _ -> if (chapter == 1) null else 10 },
            onMapped = { chapter, pages -> counts.record(chapter, pages) },
        )
        assertNull(counts.reduce(1, 3))
        assertEquals(listOf(1), counts.missingBefore(3))
        val retried = mutableListOf<Int>()
        mapReduce.map(
            counts.missingBefore(3),
            measure = { chapter, _ -> retried += chapter; 20 },
            onMapped = { chapter, pages -> counts.record(chapter, pages) },
        )
        assertEquals(listOf(1), retried)
        assertEquals(ChapterPageCounts.Totals(10, 40), counts.reduce(1, 3))
    }

    @Test
    fun `cancelling a layout cancels all active maps and leaves queued chapters untouched`() = runBlocking {
        val active = AtomicInteger()
        val started = AtomicInteger()
        val firstPair = CompletableDeferred<Unit>()
        val job = launch {
            PaginationMapReduce(2).map(
                chapters = (0 until 20).toList(),
                measure = { _, _ ->
                    active.incrementAndGet()
                    if (started.incrementAndGet() == 2) firstPair.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        active.decrementAndGet()
                    }
                },
                onMapped = { _, _ -> error("Cancelled maps must not publish results") },
            )
        }
        withTimeout(3000) { firstPair.await() }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, started.get())
        assertEquals(0, active.get())
    }
}
