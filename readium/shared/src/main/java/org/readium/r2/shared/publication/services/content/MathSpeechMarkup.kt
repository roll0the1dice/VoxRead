/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(ExperimentalReadiumApi::class)

package org.readium.r2.shared.publication.services.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * One slice of the text actually sent to TTS, and the MathML nodes it speaks.
 * Offsets are UTF-16 indexes and the end is exclusive.
 */
@ExperimentalReadiumApi
public data class MathSpeechAnchor(
    val start: Int,
    val end: Int,
    val nodeIds: List<String>,
)

/**
 * One MathCAT conversion: the formula that is displayed and the speech that is spoken.
 * [version] is the MathCAT version, speech style, and language, so a cache can tell
 * two conversions of the same formula apart.
 */
@ExperimentalReadiumApi
public data class MathSpeechBundle(
    val canonicalMathMl: String,
    val spokenText: String,
    val anchors: List<MathSpeechAnchor>,
    val version: String,
)

/**
 * Turns MathCAT's bookmarked SSML into plain text plus node ranges.
 * The plain text is what the existing TTS engine receives. Bookmarks become
 * half-open UTF-16 ranges on that text. Structural words that MathCAT does not
 * bookmark, such as "over" or a leading "square root", are attached to the
 * enclosing element.
 */
@ExperimentalReadiumApi
public object MathSpeechMarkup {
    private val markTag = Regex("""<mark\s+name\s*=\s*['"]([^'"]+)['"]\s*/>""")
    private val breakTag = Regex("""<break\s+time\s*=\s*['"](\d+)ms['"]\s*/>""")
    private val rowLabel = Regex("""列\s*(\d+)|row\s+(\d+)""")
    private val structures = setOf(
        "msqrt", "mroot", "mfrac", "msup", "msub", "msubsup",
        "munder", "mover", "munderover", "mtable",
    )
    private val glueWords = listOf("divided by", "over", "分之")
    private val traditional = listOf(
        "等於" to "等于",
        "大於" to "大于",
        "小於" to "小于",
        "趨近於" to "趋近于",
        "極限" to "极限",
        "根號" to "根号",
        "下標" to "下标",
        "上標" to "上标",
    )

    public fun parse(markup: String): MathSpeechBundle =
        bundle("", collapse(read(markup)), "")

    /**
     * Attaches bookmarked ranges to the nodes of [canonicalMathMl], which must be
     * the tree from the same MathCAT call that produced [markup].
     */
    public fun align(markup: String, canonicalMathMl: String): MathSpeechBundle {
        val spoken = collapse(read(markup))
        if (canonicalMathMl.isBlank()) return bundle(canonicalMathMl, spoken, "")
        return bundle(canonicalMathMl, assign(spoken, canonicalMathMl), "")
    }

    public fun simplify(bundle: MathSpeechBundle): MathSpeechBundle {
        val shifted = applyTraditional(bundle.spokenText, bundle.anchors)
        return bundle.copy(spokenText = shifted.first, anchors = shifted.second)
    }

    /**
     * Moves anchors from [spoken] into [normalized], which is that spoken text
     * after the utterance's own whitespace cleanup. [spanStart] is where
     * [normalized] begins in the utterance.
     */
    public fun place(
        normalized: String,
        spanStart: Int,
        spoken: String,
        anchors: List<MathSpeechAnchor>,
    ): List<MathSpeechAnchor> {
        if (spoken.isEmpty() || anchors.isEmpty()) return emptyList()
        val at = when {
            normalized.startsWith(spoken) -> 0
            normalized.startsWith(" $spoken") -> 1
            else -> normalized.indexOf(spoken)
        }
        if (at < 0) return emptyList()
        val base = spanStart + at
        return anchors.map { anchor ->
            anchor.copy(start = base + anchor.start, end = base + anchor.end)
        }
    }

    public fun simplifyText(speech: String): String = applyTraditional(speech, emptyList()).first

    /**
     * Speaks a presentation tree without MathCAT. Spacing such as `mspace` is
     * silent. Each spoken piece points at a node in the returned MathML.
     */
    public fun structure(mathml: String, idPrefix: String = "voxfb"): MathSpeechBundle {
        val document = Jsoup.parse(mathml, "", Parser.xmlParser())
        document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml).prettyPrint(false)
        val math = document.selectFirst("math") ?: return MathSpeechBundle(mathml, "", emptyList(), "structured")
        val writer = StructureWriter()
        var nextId = 0
        fun identify(element: Element): String {
            val existing = element.id()
            if (existing.isNotBlank()) return existing
            val id = "$idPrefix-${nextId++}"
            element.attr("id", id)
            return id
        }
        fun speak(element: Element) {
            when (element.normalName()) {
                "annotation", "annotation-xml", "mspace", "malignmark", "maligngroup", "mphantom" -> Unit
                "semantics" -> element.children().firstOrNull { it.normalName() != "annotation" && it.normalName() != "annotation-xml" }?.let { speak(it) }
                "math", "mrow", "mstyle", "mpadded", "menclose", "mtd", "mtr", "mlabeledtr" ->
                    element.children().forEach { speak(it) }
                "mfrac" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.size >= 2) {
                        speak(kids[1])
                        writer.word("分之", id)
                        speak(kids[0])
                    }
                }
                "msub" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.isNotEmpty()) speak(kids[0])
                    writer.word("下标", id)
                    if (kids.size > 1) speak(kids[1])
                }
                "msup" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.isNotEmpty()) speak(kids[0])
                    writer.word("上标", id)
                    if (kids.size > 1) speak(kids[1])
                }
                "msubsup" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.isNotEmpty()) speak(kids[0])
                    writer.word("下标", id)
                    if (kids.size > 1) speak(kids[1])
                    writer.word("上标", id)
                    if (kids.size > 2) speak(kids[2])
                }
                "msqrt" -> {
                    val id = identify(element)
                    writer.word("根号", id)
                    element.children().forEach { speak(it) }
                }
                "mroot" -> {
                    val id = identify(element)
                    writer.word("根号", id)
                    element.children().forEach { speak(it) }
                }
                "mtable" -> {
                    val id = identify(element)
                    writer.word("矩阵", id)
                    element.children().forEach { speak(it) }
                }
                else -> {
                    if (element.children().isEmpty()) {
                        writer.word(structureToken(element.text()), identify(element))
                    } else {
                        element.children().forEach { speak(it) }
                    }
                }
            }
        }
        speak(math)
        return MathSpeechBundle(math.outerHtml(), writer.text(), writer.anchors(), "structured")
    }

    private class StructureWriter {
        private val text = StringBuilder()
        private val anchors = mutableListOf<MathSpeechAnchor>()

        fun word(value: String, nodeId: String) {
            if (value.isEmpty()) return
            if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
            val start = text.length
            text.append(value)
            if (nodeId.isNotBlank()) anchors += MathSpeechAnchor(start, text.length, listOf(nodeId))
        }

        fun text(): String = text.toString()

        fun anchors(): List<MathSpeechAnchor> = anchors
    }

    private fun structureToken(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty() || text == ".") return ""
        tokens[text]?.let { return it }
        if (text.startsWith("\\")) return ""
        return text
    }

    private val tokens = mapOf(
        "=" to "等于",
        "+" to "加",
        "-" to "减",
        "−" to "减",
        "≍" to "等价于",
        "≈" to "约等于",
        "→" to "箭头",
        "⟶" to "箭头",
        "," to "逗号",
        "(" to "左括号",
        ")" to "右括号",
        "|" to "绝对值",
        "ℓ" to "ell",
        "ν" to "nu",
        "τ" to "tau",
        "π" to "pi",
        "θ" to "theta",
        "η" to "eta",
        "μ" to "mu",
        "λ" to "lambda",
        "α" to "alpha",
        "β" to "beta",
        "γ" to "gamma",
        "δ" to "delta",
        "∞" to "无穷",
    )

    private data class Spoken(
        val text: String,
        val anchors: List<MathSpeechAnchor>,
    )

    private data class Mark(val at: Int, val id: String)

    private fun bundle(mathml: String, spoken: Spoken, version: String) = MathSpeechBundle(
        canonicalMathMl = mathml,
        spokenText = spoken.text,
        anchors = spoken.anchors,
        version = version,
    )

    private fun read(markup: String): Pair<String, List<Mark>> {
        val raw = StringBuilder()
        val marks = mutableListOf<Mark>()
        var pendingSpace = false
        var index = 0
        while (index < markup.length) {
            if (markup[index] == '<') {
                val close = markup.indexOf('>', index)
                if (close < 0) break
                val tag = markup.substring(index, close + 1)
                val named = markTag.find(tag)
                if (named != null) {
                    marks += Mark(raw.length, named.groupValues[1])
                } else {
                    val pause = breakTag.find(tag)
                    if (pause != null) {
                        val millis = pause.groupValues[1].toIntOrNull() ?: 0
                        trimOneTrailingSpace(raw)
                        raw.append(if (millis <= 250) ',' else ';')
                        pendingSpace = true
                    }
                }
                index = close + 1
                continue
            }
            val next = markup.indexOf('<', index).let { if (it < 0) markup.length else it }
            val text = unescape(markup.substring(index, next))
            if (text.isNotEmpty()) {
                if (pendingSpace && !text.first().isWhitespace()) raw.append(' ')
                pendingSpace = false
                raw.append(text)
            }
            index = next
        }
        trimTrailingPause(raw)
        return raw.toString() to marks
    }

    private fun collapse(read: Pair<String, List<Mark>>): Spoken {
        val (raw, marks) = read
        val normalized = SpeechMap.normalize(raw, stripLeading = false)
        if (normalized.text.isEmpty()) return Spoken("", emptyList())
        val owners = Array(normalized.text.length) { "" }
        for (piece in normalized.pieces) {
            val id = ownerAt(marks, piece.rawStart)
            for (cursor in piece.outStart until piece.outEnd) {
                if (cursor < owners.size) owners[cursor] = id
            }
        }
        var end = normalized.text.length
        while (end > 0 && normalized.text[end - 1] in " ,;") {
            end--
        }
        var begin = 0
        while (begin < end && normalized.text[begin] == ' ') begin++
        val text = normalized.text.substring(begin, end)
        val anchors = mutableListOf<MathSpeechAnchor>()
        var cursor = begin
        while (cursor < end) {
            val id = owners[cursor]
            var next = cursor + 1
            while (next < end && owners[next] == id) next++
            val ids = if (id.isEmpty()) emptyList() else listOf(id)
            anchors += MathSpeechAnchor(cursor - begin, next - begin, ids)
            cursor = next
        }
        return Spoken(text, anchors)
    }

    private fun assign(spoken: Spoken, mathml: String): Spoken {
        val document = Jsoup.parse(mathml, "", Parser.xmlParser())
        val byId = HashMap<String, Element>()
        for (element in document.allElements) {
            val id = element.id()
            if (id.isNotBlank()) byId[id] = element
        }
        val rows = document.select("mtr, mlabeledtr").map { it.id() }.filter { it.isNotBlank() }
        val firstMarked = spoken.anchors.firstOrNull { it.nodeIds.isNotEmpty() }?.nodeIds?.firstOrNull()
        val leadingOwner = structureId(byId[firstMarked])
        val aligned = mutableListOf<MathSpeechAnchor>()
        for (anchor in spoken.anchors) {
            val owner = anchor.nodeIds.firstOrNull()?.let { presentationId(it, byId) }
            val element = owner?.let { byId[it] }
            val located = if (owner == null) anchor else anchor.copy(nodeIds = listOf(owner))
            val pieces = if (owner != null && element != null) {
                splitGlue(spoken.text, located, element.ownText().trim(), fractionId(element))
            } else if (owner == null && leadingOwner != null) {
                listOf(located.copy(nodeIds = listOf(leadingOwner)))
            } else {
                listOf(located)
            }
            for (piece in pieces) {
                aligned += splitRows(spoken.text, piece, rows)
            }
        }
        return Spoken(spoken.text, aligned.filter { it.end > it.start && it.nodeIds.isNotEmpty() })
    }

    private fun splitGlue(
        text: String,
        anchor: MathSpeechAnchor,
        leaf: String,
        fractionId: String?,
    ): List<MathSpeechAnchor> {
        if (leaf.isEmpty() || fractionId == null) return listOf(anchor)
        val slice = text.substring(anchor.start, anchor.end)
        val contentStart = slice.indexOfFirst { it != ' ' && it != ',' && it != ';' }
        if (contentStart < 0 || !slice.regionMatches(contentStart, leaf, 0, leaf.length)) {
            return listOf(anchor)
        }
        val afterLeaf = contentStart + leaf.length
        val restStart = (afterLeaf until slice.length).firstOrNull { index ->
            val ch = slice[index]
            ch != ' ' && ch != ',' && ch != ';'
        } ?: return listOf(anchor)
        val rest = slice.substring(restStart).trim { it == ' ' || it == ',' || it == ';' }
        val glue = glueWords.firstOrNull { word ->
            rest == word || rest.startsWith("$word ") || rest.startsWith("$word,") || rest.startsWith("$word;")
        } ?: return listOf(anchor)
        if (!rest.startsWith(glue)) return listOf(anchor)
        val leafEnd = anchor.start + afterLeaf
        val glueStart = anchor.start + restStart
        return listOf(
            MathSpeechAnchor(anchor.start, leafEnd, anchor.nodeIds),
            MathSpeechAnchor(glueStart, anchor.end, listOf(fractionId)),
        ).filter { it.end > it.start }
    }

    private fun splitRows(
        text: String,
        anchor: MathSpeechAnchor,
        rows: List<String>,
    ): List<MathSpeechAnchor> {
        if (anchor.nodeIds.isEmpty()) return emptyList()
        val slice = text.substring(anchor.start, anchor.end)
        val matches = rowLabel.findAll(slice).toList()
        if (matches.isEmpty()) return listOf(anchor)
        val result = mutableListOf<MathSpeechAnchor>()
        var cursor = 0
        for (match in matches) {
            val number = (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull() ?: continue
            val rowId = rows.getOrNull(number - 1) ?: continue
            if (anchor.nodeIds.singleOrNull() == rowId) continue
            if (match.range.first > cursor) {
                result += MathSpeechAnchor(
                    anchor.start + cursor,
                    anchor.start + match.range.first,
                    anchor.nodeIds,
                )
            }
            result += MathSpeechAnchor(
                anchor.start + match.range.first,
                anchor.start + match.range.last + 1,
                listOf(rowId),
            )
            cursor = match.range.last + 1
        }
        if (cursor < slice.length) {
            result += MathSpeechAnchor(anchor.start + cursor, anchor.end, anchor.nodeIds)
        }
        return result.filter { it.end > it.start }
    }

    /**
     * Intent rewriting can bookmark an intermediate node such as
     * `id-indexed-by`. The fullscreen tree is the presentation MathML, which
     * keeps the id without that suffix.
     */
    private fun presentationId(id: String, byId: Map<String, Element>): String? {
        if (byId.containsKey(id)) return id
        val suffix = "-indexed-by"
        if (id.endsWith(suffix)) {
            val base = id.removeSuffix(suffix)
            if (byId.containsKey(base)) return base
        }
        return null
    }

    private fun structureId(start: Element?): String? {
        var current = start?.parent()
        while (current != null) {
            if (current.normalName() in structures && current.id().isNotBlank()) return current.id()
            current = current.parent()
        }
        return null
    }

    private fun fractionId(start: Element?): String? {
        var current = start?.parent()
        while (current != null) {
            if (current.normalName() == "mfrac" && current.id().isNotBlank()) return current.id()
            current = current.parent()
        }
        return null
    }

    private fun applyTraditional(
        text: String,
        anchors: List<MathSpeechAnchor>,
    ): Pair<String, List<MathSpeechAnchor>> {
        if (traditional.none { text.contains(it.first) }) return text to anchors
        val built = StringBuilder()
        val mapped = IntArray(text.length + 1)
        var index = 0
        while (index < text.length) {
            mapped[index] = built.length
            val pair = traditional.firstOrNull { text.startsWith(it.first, index) }
            if (pair != null) {
                built.append(pair.second)
                for (step in 1 until pair.first.length) mapped[index + step] = built.length
                index += pair.first.length
            } else {
                built.append(text[index])
                index++
            }
        }
        mapped[text.length] = built.length
        val shifted = anchors.map { anchor ->
            anchor.copy(
                start = mapped[anchor.start.coerceIn(0, text.length)],
                end = mapped[anchor.end.coerceIn(0, text.length)],
            )
        }.filter { it.end > it.start }
        return built.toString() to shifted
    }

    private fun ownerAt(marks: List<Mark>, rawIndex: Int): String {
        var id = ""
        for (mark in marks) {
            if (mark.at > rawIndex) break
            id = mark.id
        }
        return id
    }

    private fun trimOneTrailingSpace(raw: StringBuilder) {
        if (raw.isNotEmpty() && raw[raw.length - 1] == ' ') {
            raw.setLength(raw.length - 1)
        }
    }

    private fun trimTrailingPause(raw: StringBuilder) {
        while (raw.isNotEmpty() && raw[raw.length - 1] in " ,;") {
            raw.setLength(raw.length - 1)
        }
    }

    private fun unescape(text: String): String =
        text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
