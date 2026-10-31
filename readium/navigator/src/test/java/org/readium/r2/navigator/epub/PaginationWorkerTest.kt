package org.readium.r2.navigator.epub

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationWorkerTest {
    @Test
    fun `pagination uses its own low priority thread and resumes on the caller`() = runBlocking {
        val caller = Thread.currentThread()
        PaginationWorker().use { worker ->
            val background = worker.run {
                Thread.currentThread().also { thread ->
                    assertNotSame(caller, thread)
                    assertTrue(thread.name.startsWith("VoxRead-pagination-"))
                    assertEquals(Thread.MIN_PRIORITY, thread.priority)
                }
            }
            assertSame(caller, Thread.currentThread())
            assertNotSame(caller, background)
            assertNotSame(caller, worker.run { Thread.currentThread() })
        }
    }

    @Test
    fun `cancelled queued work never runs after a newer request`() = runBlocking {
        val started = CountDownLatch(PaginationWorker.PARALLELISM)
        val release = CountDownLatch(1)
        val obsoleteTaskRan = AtomicBoolean(false)
        PaginationWorker().use { worker ->
            try {
                val occupied = List(PaginationWorker.PARALLELISM) {
                    async(start = CoroutineStart.UNDISPATCHED) {
                        worker.run {
                            started.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                        }
                    }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
                    worker.run { obsoleteTaskRan.set(true) }
                }
                obsolete.cancel()
                release.countDown()
                occupied.awaitAll()
                obsolete.join()
                assertEquals(42, worker.run { 42 })
                assertFalse(obsoleteTaskRan.get())
            } finally {
                release.countDown()
            }
        }
    }
}
