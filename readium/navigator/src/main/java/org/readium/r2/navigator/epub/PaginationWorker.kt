package org.readium.r2.navigator.epub

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Bounded, low-priority work that does not share the UI or TTS executor. */
internal class PaginationWorker : Closeable {
    companion object {
        const val PARALLELISM = 8
    }

    private val threadNumber = AtomicInteger()
    private val dispatcher = Executors.newFixedThreadPool(PARALLELISM) { task ->
        Thread(task, "VoxRead-pagination-${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    suspend fun <T> run(task: () -> T): T = withContext(dispatcher) { task() }

    override fun close() = dispatcher.close()
}
