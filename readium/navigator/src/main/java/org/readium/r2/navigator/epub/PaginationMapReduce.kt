package org.readium.r2.navigator.epub

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Maps chapter counts with a fixed number of reusable renderer slots. */
internal class PaginationMapReduce(private val parallelism: Int = PaginationWorker.PARALLELISM) {
    @Volatile private var activeMap: Job? = null

    /** Cancel just the maps so foreground navigation does not cancel the reader/TTS scope. */
    fun cancel() {
        activeMap?.cancel()
    }

    init {
        require(parallelism > 0)
    }

    suspend fun map(
        chapters: List<Int>,
        measure: suspend (chapter: Int, slot: Int) -> Int?,
        onMapped: suspend (chapter: Int, pages: Int) -> Unit,
    ) = coroutineScope {
        if (chapters.isEmpty()) return@coroutineScope
        val queue = Channel<Int>(chapters.size)
        chapters.forEach { queue.trySend(it).getOrThrow() }
        queue.close()
        val mapJob = currentCoroutineContext().job
        activeMap = mapJob
        try {
            List(minOf(parallelism, chapters.size)) { slot ->
                async {
                    for (chapter in queue) {
                        val pages = measure(chapter, slot) ?: continue
                        require(pages > 0)
                        onMapped(chapter, pages)
                    }
                }
            }.awaitAll()
        } finally {
            queue.cancel()
            if (activeMap === mapJob) activeMap = null
        }
    }
}
