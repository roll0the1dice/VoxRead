/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalReadiumApi::class)

package org.readium.r2.shared.publication.services.content

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * Maps a span of text that will be spoken onto the original document.
 *
 * Offsets are UTF-16 indexes into the utterance actually sent to the engine.
 * A math span covers the whole spoken form of one formula and always points at
 * that formula. A text span points at one DOM text node and the characters
 * inside it. Chapter scope lives in [SpeechAnchors]; the page script uses the
 * same `vox:{href}#{localId}` form.
 */
@ExperimentalReadiumApi
public class SpeechMap(
    public val spans: List<Span>,
) {

    public enum class Kind {
        Text,
        Math,
    }

    public data class Span(
        val start: Int,
        val end: Int,
        val kind: Kind,
        val selector: String,
        val node: Int = 0,
        val from: Int = 0,
        val to: Int = 0,
        val raw: String = "",
        val mathId: String = "",
        val prefix: String = "",
        val suffix: String = "",
    )

    /**
     * @param sentenceLevel True when the engine has not reported a word range.
     * Every span of the utterance is highlighted together. A word range never
     * invents a position inside a formula: the whole formula stays lit.
     */
    public data class Highlight(
        val sentenceLevel: Boolean,
        val spans: List<Span>,
    )

    public fun slice(start: Int, endExclusive: Int): SpeechMap {
        val cutStart = start.coerceAtLeast(0)
        val cutEnd = endExclusive.coerceAtLeast(cutStart)
        return SpeechMap(
            spans.mapNotNull { span ->
                if (span.end <= cutStart || span.start >= cutEnd) {
                    null
                } else {
                    span.slice(cutStart, cutEnd)
                }
            }
        )
    }

    /**
     * Resolves a TTS range. A null range is sentence level. A range uses the
     * span that contains its first index, so a formula and the following word
     * are not highlighted as if they were spoken together.
     */
    /**
     * The formula under a word range. A missing range is sentence level: one
     * formula is reported only when the utterance is that formula alone.
     */
    public fun spokenMathId(range: IntRange?): String? {
        val active = resolve(range)
        if (active.sentenceLevel) {
            val mathIds = spans.mapNotNull { span ->
                span.mathId.takeIf { span.kind == Kind.Math && it.isNotBlank() }
            }.distinct()
            val onlyMath = spans.isNotEmpty() && spans.all { it.kind == Kind.Math }
            if (onlyMath && mathIds.size == 1) return mathIds[0]
            return null
        }
        return active.spans.mapNotNull { span ->
            span.mathId.takeIf { span.kind == Kind.Math && it.isNotBlank() }
        }.distinct().singleOrNull()
    }

    public fun resolve(range: IntRange?): Highlight {
        if (range == null || spans.isEmpty()) {
            return Highlight(sentenceLevel = true, spans = spans)
        }
        val anchor = range.first.coerceAtLeast(0)
        val span = spans.firstOrNull { anchor >= it.start && anchor < it.end }
            ?: return Highlight(sentenceLevel = true, spans = spans)
        if (span.kind == Kind.Math) {
            return Highlight(sentenceLevel = false, spans = listOf(span))
        }
        val wordEnd = (range.last + 1).coerceIn(anchor, span.end)
        return Highlight(
            sentenceLevel = false,
            spans = slice(anchor, wordEnd).spans.filter { it.kind == Kind.Text }
        )
    }

    public fun toStored(): List<Map<String, Any>> =
        spans.map { span ->
            mapOf(
                "start" to span.start,
                "end" to span.end,
                "kind" to if (span.kind == Kind.Math) "math" else "text",
                "selector" to span.selector,
                "node" to span.node,
                "from" to span.from,
                "to" to span.to,
                "raw" to span.raw,
                "mathId" to span.mathId,
            )
        }

    public companion object {
        public const val KEY: String = "speechMap"

        public fun from(locator: Locator): SpeechMap? {
            if (!locator.locations.otherLocations.containsKey(KEY)) return null
            val stored = locator.locations.otherLocations[KEY] as? List<*> ?: return SpeechMap(emptyList())
            val spans = stored.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val start = map["start"].asInt() ?: return@mapNotNull null
                val end = map["end"].asInt() ?: return@mapNotNull null
                Span(
                    start = start,
                    end = end,
                    kind = if (map["kind"] == "math") Kind.Math else Kind.Text,
                    selector = map["selector"] as? String ?: "",
                    node = map["node"].asInt() ?: 0,
                    from = map["from"].asInt() ?: 0,
                    to = map["to"].asInt() ?: 0,
                    raw = map["raw"] as? String ?: "",
                    mathId = map["mathId"] as? String ?: "",
                )
            }
            return SpeechMap(spans)
        }

        /**
         * Same whitespace collapsing as HTML text extraction. Each piece records
         * the raw code-unit range that produced one emitted code point.
         */
        public fun normalize(raw: String, stripLeading: Boolean): Normalized {
            val out = StringBuilder()
            val pieces = mutableListOf<Piece>()
            var lastWasWhite = false
            var reachedNonWhite = false
            var index = 0
            while (index < raw.length) {
                val codePoint = raw.codePointAt(index)
                val count = Character.charCount(codePoint)
                if (isWhitespace(codePoint)) {
                    if ((stripLeading && !reachedNonWhite) || lastWasWhite) {
                        index += count
                        continue
                    }
                    val outStart = out.length
                    out.append(' ')
                    pieces.add(Piece(outStart, out.length, index, index + count))
                    lastWasWhite = true
                } else if (!isInvisible(codePoint)) {
                    val outStart = out.length
                    out.appendCodePoint(codePoint)
                    pieces.add(Piece(outStart, out.length, index, index + count))
                    lastWasWhite = false
                    reachedNonWhite = true
                }
                index += count
            }
            return Normalized(out.toString(), pieces)
        }
    }
}

@ExperimentalReadiumApi
public data class Normalized(
    val text: String,
    val pieces: List<Piece>,
)

@ExperimentalReadiumApi
public data class Piece(
    val outStart: Int,
    val outEnd: Int,
    val rawStart: Int,
    val rawEnd: Int,
)

/**
 * Formula ids shared by content extraction and the page script.
 *
 * `vox:{href}#{localId}` — localId is the author id, or `vox-math-{index}`
 * in document order when the formula has none. The page looks up `localId`.
 */
@ExperimentalReadiumApi
public object SpeechAnchors {
    public const val PREFIX: String = "vox:"

    public fun localMathId(existingId: String, index: Int): String =
        existingId.ifBlank { "vox-math-$index" }

    public fun scope(href: String, localId: String): String =
        "$PREFIX$href#$localId"

    public fun localId(scopedOrLocal: String): String {
        if (!scopedOrLocal.startsWith(PREFIX)) return scopedOrLocal
        val hash = scopedOrLocal.lastIndexOf('#')
        if (hash < PREFIX.length) return scopedOrLocal
        return scopedOrLocal.substring(hash + 1)
    }

    public fun href(scoped: String): String? {
        if (!scoped.startsWith(PREFIX)) return null
        val hash = scoped.lastIndexOf('#')
        if (hash < PREFIX.length) return null
        return scoped.substring(PREFIX.length, hash)
    }
}

private fun SpeechMap.Span.slice(cutStart: Int, cutEnd: Int): SpeechMap.Span? {
    val newStart = (start - cutStart).coerceAtLeast(0)
    val newEnd = (end.coerceAtMost(cutEnd) - cutStart)
    if (newEnd <= newStart) return null
    if (kind == SpeechMap.Kind.Math || raw.isEmpty()) {
        return copy(start = newStart, end = newEnd)
    }
    val localStart = (cutStart - start).coerceAtLeast(0)
    val localEnd = (cutEnd - start).coerceAtMost(end - start)
    val normalized = SpeechMap.normalize(raw, stripLeading = false)
    if (normalized.text.length != end - start) {
        return copy(start = newStart, end = newEnd)
    }
    val covered = normalized.pieces.filter { it.outEnd > localStart && it.outStart < localEnd }
    if (covered.isEmpty()) return null
    val rawStart = covered.first().rawStart
    val rawEnd = covered.last().rawEnd
    return copy(
        start = newStart,
        end = newEnd,
        from = from + rawStart,
        to = from + rawEnd,
        raw = raw.substring(rawStart, rawEnd),
        prefix = raw.substring(0, rawStart),
        suffix = raw.substring(rawEnd),
    )
}

private fun Any?.asInt(): Int? = when (this) {
    is Int -> this
    is Long -> toInt()
    is Double -> toInt()
    is Float -> toInt()
    is String -> toIntOrNull()
    else -> null
}

private fun isWhitespace(codePoint: Int): Boolean =
    codePoint == ' '.code || codePoint == '\t'.code || codePoint == '\n'.code ||
        codePoint == '\u000c'.code || codePoint == '\r'.code

private fun isInvisible(codePoint: Int): Boolean =
    codePoint == 8203 || codePoint == 173
