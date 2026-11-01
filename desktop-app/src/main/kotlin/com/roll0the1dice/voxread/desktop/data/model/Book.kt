package com.roll0the1dice.voxread.desktop.data.model

import com.roll0the1dice.voxread.desktop.tts.EdgeTtsConstants
import java.io.File
import kotlin.math.roundToInt

data class ReadingProgressionData(
    val chapterIndex: Int = 0,
    val chapterHref: String = "",
    val chapterTitle: String = "",
    val blockIndex: Int = 0,
    val scrollOffset: Int = 0,
    val utteranceIndex: Int = 0,
    val chapterProgression: Double = 0.0,
    val totalProgression: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val fontSize: Int = 18,
    val theme: String = "light",
    val ttsGain: Double = EdgeTtsConstants.DEFAULT_GAIN_FACTOR,
    val ttsDrc: Boolean = EdgeTtsConstants.DEFAULT_DRC_ENABLED,
    val hasBlockPosition: Boolean = true
) {
    val percent: Int
        get() = (totalProgression * 100.0).roundToInt().coerceIn(0, 100)

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    fun toJsonString(): String {
        val cProg = chapterProgression.coerceIn(0.0, 1.0)
        val tProg = totalProgression.coerceIn(0.0, 1.0)
        val gVal = ttsGain.coerceIn(0.5, 3.0)
        return """{"href":"${escapeJson(chapterHref)}","title":"${escapeJson(chapterTitle)}","chapterIndex":$chapterIndex,"blockIndex":$blockIndex,"scrollOffset":$scrollOffset,"utteranceIndex":$utteranceIndex,"updatedAt":$updatedAt,"fontSize":$fontSize,"theme":"${escapeJson(theme)}","ttsGain":$gVal,"ttsDrc":$ttsDrc,"locations":{"progression":$cProg,"totalProgression":$tProg,"position":${chapterIndex + 1}}}"""
    }

    companion object {
        private fun extractString(json: String, key: String): String? {
            val regex = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
            return regex.find(json)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.replace("\\r", "\r")
                ?.replace("\\t", "\t")
                ?.replace("\\\\", "\\")
        }

        private fun extractDouble(json: String, key: String): Double? {
            val regex = Regex(""""$key"\s*:\s*(-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)""")
            return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        private fun extractInt(json: String, key: String): Int? {
            val regex = Regex(""""$key"\s*:\s*(-?[0-9]+)""")
            return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
        }

        private fun extractLong(json: String, key: String): Long? {
            val regex = Regex(""""$key"\s*:\s*(-?[0-9]+)""")
            return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
        }

        private fun extractBoolean(json: String, key: String): Boolean? {
            val regex = Regex(""""$key"\s*:\s*(true|false)""")
            return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        }

        fun fromJsonString(raw: String?): ReadingProgressionData? {
            if (raw.isNullOrBlank() || raw.trim() == "{}") return null
            val trimmed = raw.trim()
            trimmed.toDoubleOrNull()?.let { num ->
                val frac = num.coerceIn(0.0, 1.0)
                return ReadingProgressionData(
                    chapterProgression = frac,
                    totalProgression = frac,
                    updatedAt = 0L,
                    hasBlockPosition = false
                )
            }
            if (!trimmed.startsWith("{")) return null

            val totalProg = extractDouble(trimmed, "totalProgression")
                ?: extractDouble(trimmed, "progression")
                ?: 0.0
            val chapProg = extractDouble(trimmed, "progression") ?: totalProg
            val chapIdx = extractInt(trimmed, "chapterIndex")
                ?: extractInt(trimmed, "position")?.minus(1)?.coerceAtLeast(0)
                ?: 0

            return ReadingProgressionData(
                chapterIndex = chapIdx.coerceAtLeast(0),
                chapterHref = extractString(trimmed, "href") ?: "",
                chapterTitle = extractString(trimmed, "title") ?: "",
                blockIndex = extractInt(trimmed, "blockIndex")?.coerceAtLeast(0) ?: 0,
                scrollOffset = extractInt(trimmed, "scrollOffset")?.coerceAtLeast(0) ?: 0,
                utteranceIndex = extractInt(trimmed, "utteranceIndex")?.coerceAtLeast(0) ?: 0,
                chapterProgression = chapProg.coerceIn(0.0, 1.0),
                totalProgression = totalProg.coerceIn(0.0, 1.0),
                updatedAt = extractLong(trimmed, "updatedAt") ?: 0L,
                fontSize = extractInt(trimmed, "fontSize")?.coerceIn(14, 32) ?: 18,
                theme = extractString(trimmed, "theme") ?: "light",
                ttsGain = extractDouble(trimmed, "ttsGain")?.coerceIn(0.5, 3.0) ?: EdgeTtsConstants.DEFAULT_GAIN_FACTOR,
                ttsDrc = extractBoolean(trimmed, "ttsDrc") ?: EdgeTtsConstants.DEFAULT_DRC_ENABLED,
                hasBlockPosition = extractInt(trimmed, "blockIndex") != null
            )
        }
    }
}

data class Book(
    val id: Long? = null,
    val creation: Long = System.currentTimeMillis(),
    val href: String,
    val title: String,
    val author: String? = null,
    val identifier: String = "",
    val progression: String? = null,
    val mediaType: String = "application/epub+zip",
    val cover: String? = null
) {
    val file: File get() = File(href)
    val exists: Boolean get() = file.exists()
    val displayName: String get() = if (title.isNotBlank()) title else file.name
    val parsedProgression: ReadingProgressionData? get() = ReadingProgressionData.fromJsonString(progression)
    val readingFraction: Double? get() = parsedProgression?.totalProgression

    companion object {
        const val TABLE_NAME = "books"
        const val ID = "id"
        const val CREATION_DATE = "creation_date"
        const val HREF = "href"
        const val TITLE = "title"
        const val AUTHOR = "author"
        const val IDENTIFIER = "identifier"
        const val PROGRESSION = "progression"
        const val MEDIA_TYPE = "media_type"
        const val COVER = "cover"
    }
}

data class Bookmark(
    val id: Long? = null,
    val creation: Long = System.currentTimeMillis(),
    val bookId: Long,
    val resourceIndex: Long,
    val resourceHref: String,
    val resourceType: String,
    val resourceTitle: String,
    val location: String,
    val locatorText: String
) {
    companion object {
        const val TABLE_NAME = "bookmarks"
        const val ID = "id"
        const val CREATION_DATE = "creation_date"
        const val BOOK_ID = "book_id"
        const val RESOURCE_INDEX = "resource_index"
        const val RESOURCE_HREF = "resource_href"
        const val RESOURCE_TYPE = "resource_type"
        const val RESOURCE_TITLE = "resource_title"
        const val LOCATION = "location"
        const val LOCATOR_TEXT = "locator_text"
    }
}

data class Highlight(
    val id: Long? = null,
    val creation: Long = System.currentTimeMillis(),
    val bookId: Long,
    val style: String,
    val color: Int,
    val annotation: String? = null,
    val location: String,
    val locatorText: String
) {
    companion object {
        const val TABLE_NAME = "highlights"
        const val ID = "id"
        const val CREATION_DATE = "creation_date"
        const val BOOK_ID = "book_id"
        const val STYLE = "style"
        const val COLOR = "color"
        const val ANNOTATION = "annotation"
        const val LOCATION = "location"
        const val LOCATOR_TEXT = "locator_text"
    }
}
