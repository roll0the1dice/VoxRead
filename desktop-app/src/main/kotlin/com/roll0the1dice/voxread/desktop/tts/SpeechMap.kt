package com.roll0the1dice.voxread.desktop.tts

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class MathSpeechAnchor(
    val start: Int,
    val end: Int,
    val nodeIds: List<String>
)

data class MathSpeechBundle(
    val canonicalMathMl: String,
    val spokenText: String,
    val anchors: List<MathSpeechAnchor>,
    val version: String
)

data class Piece(
    val outStart: Int,
    val outEnd: Int,
    val rawStart: Int,
    val rawEnd: Int
)

data class Normalized(
    val text: String,
    val pieces: List<Piece>
)

object SpeechAnchors {
    const val PREFIX: String = "vox:"

    fun localMathId(existingId: String, index: Int): String =
        if (existingId.isNotBlank()) existingId else "vox-math-$index"

    fun scope(href: String, localId: String): String =
        "$PREFIX$href#$localId"

    fun localId(scopedOrLocal: String): String =
        if (!scopedOrLocal.startsWith(PREFIX)) scopedOrLocal
        else {
            val hash = scopedOrLocal.lastIndexOf('#')
            if (hash < PREFIX.length) scopedOrLocal
            else scopedOrLocal.substring(hash + 1)
        }

    fun href(scoped: String): String? =
        if (!scoped.startsWith(PREFIX)) null
        else {
            val hash = scoped.lastIndexOf('#')
            if (hash < PREFIX.length) null
            else scoped.substring(PREFIX.length, hash)
        }
}

class SpeechMap(val spans: List<Span>) {

    enum class Kind { Text, Math }

    data class Span(
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
        val nodes: List<MathSpeechAnchor> = emptyList(),
        val mathml: String = ""
    ) {
        fun slice(cutStart: Int, cutEnd: Int): Span? {
            val newStart = max(0, start - cutStart)
            val newEnd = min(end, cutEnd) - cutStart
            if (newEnd <= newStart) return null
            val keptNodes = shiftNodes(cutStart, newEnd)
            if (kind == Kind.Math || raw.isEmpty()) {
                return copy(start = newStart, end = newEnd, nodes = keptNodes)
            }

            val localStart = max(0, cutStart - start)
            val localEnd = min(end - start, cutEnd - start)
            val normalized = normalize(raw, stripLeading = false)
            if (normalized.text.length != end - start) {
                return copy(start = newStart, end = newEnd, nodes = keptNodes)
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
                nodes = keptNodes
            )
        }

        private fun shiftNodes(cutStart: Int, newEnd: Int): List<MathSpeechAnchor> {
            if (nodes.isEmpty()) return emptyList()
            return nodes.mapNotNull { n ->
                val s = n.start - cutStart
                val e = n.end - cutStart
                if (e <= 0 || s >= newEnd) null
                else {
                    val clampedS = max(0, s)
                    val clampedE = min(newEnd, e)
                    if (clampedE > clampedS) n.copy(start = clampedS, end = clampedE)
                    else null
                }
            }
        }
    }

    data class Highlight(
        val sentenceLevel: Boolean,
        val spans: List<Span>,
        val nodeIds: List<String> = emptyList()
    )

    fun slice(start: Int, endExclusive: Int): SpeechMap {
        val cutStart = max(0, start)
        val cutEnd = max(cutStart, endExclusive)
        return SpeechMap(
            spans.mapNotNull { span ->
                if (span.end <= cutStart || span.start >= cutEnd) null
                else span.slice(cutStart, cutEnd)
            }
        )
    }

    fun spokenMathId(range: Pair<Int, Int>?): String? {
        val active = resolve(range)
        return if (active.sentenceLevel) {
            val mathIds = spans.filter { it.kind == Kind.Math && it.mathId.isNotBlank() }
                .map { it.mathId }.distinct()
            val onlyMath = spans.isNotEmpty() && spans.all { it.kind == Kind.Math }
            if (onlyMath && mathIds.size == 1) mathIds.first() else null
        } else {
            val mathIds = active.spans.filter { it.kind == Kind.Math && it.mathId.isNotBlank() }
                .map { it.mathId }.distinct()
            if (mathIds.size == 1) mathIds.first() else null
        }
    }

    fun resolve(range: Pair<Int, Int>?): Highlight {
        if (range == null || spans.isEmpty()) {
            return Highlight(sentenceLevel = true, spans = spans)
        }
        val (rangeFirst, rangeLastInclusive) = range
        val anchor = max(0, rangeFirst)
        val target = spans.find { anchor >= it.start && anchor < it.end }
            ?: return Highlight(sentenceLevel = true, spans = spans)

        return if (target.kind == Kind.Math) {
            val wordEnd = max(anchor, min(target.end, rangeLastInclusive + 1))
            val nodeIds = target.nodes
                .filter { it.end > anchor && it.start < wordEnd }
                .flatMap { it.nodeIds }
                .distinct()
            Highlight(sentenceLevel = false, spans = listOf(target), nodeIds = nodeIds)
        } else {
            val wordEnd = max(anchor, min(target.end, rangeLastInclusive + 1))
            Highlight(
                sentenceLevel = false,
                spans = slice(anchor, wordEnd).spans.filter { it.kind == Kind.Text }
            )
        }
    }

    companion object {
        const val KEY: String = "speechMap"

        fun normalize(raw: String, stripLeading: Boolean): Normalized {
            val out = java.lang.StringBuilder()
            val pieces = mutableListOf<Piece>()
            var lastWasWhite = false
            var reachedNonWhite = false
            var index = 0
            while (index < raw.length) {
                val codePoint = raw.codePointAt(index)
                val count = Character.charCount(codePoint)
                if (isWhitespace(codePoint)) {
                    if (!((stripLeading && !reachedNonWhite) || lastWasWhite)) {
                        val outStart = out.length
                        out.append(' ')
                        pieces.add(Piece(outStart, out.length, index, index + count))
                        lastWasWhite = true
                    }
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

        private fun isWhitespace(codePoint: Int): Boolean =
            codePoint == ' '.code || codePoint == '\t'.code || codePoint == '\n'.code ||
                    codePoint == 0x0c || codePoint == '\r'.code

        private fun isInvisible(codePoint: Int): Boolean =
            codePoint == 8203 || codePoint == 173
    }
}

object MathSpeechMarkup {
    private val tokens: Map<String, String> = mapOf(
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
        "∞" to "无穷"
    )

    fun place(
        normalized: String,
        spanStart: Int,
        spoken: String,
        anchors: List<MathSpeechAnchor>
    ): List<MathSpeechAnchor> {
        if (spoken.isEmpty() || anchors.isEmpty()) return emptyList()
        val at = when {
            normalized.startsWith(spoken) -> 0
            normalized.startsWith(" $spoken") -> 1
            else -> normalized.indexOf(spoken)
        }
        if (at < 0) return emptyList()
        val base = spanStart + at
        return anchors.map { it.copy(start = base + it.start, end = base + it.end) }
    }

    fun structure(mathml: String, idPrefix: String = "voxfb"): MathSpeechBundle {
        val document = Jsoup.parse(mathml, "", Parser.xmlParser())
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false)
        val math = document.selectFirst("math")
            ?: return MathSpeechBundle(mathml, "", emptyList(), "structured")

        val textBuf = java.lang.StringBuilder()
        val anchorsBuf = mutableListOf<MathSpeechAnchor>()
        var nextId = 0

        fun word(value: String, nodeId: String) {
            if (value.isNotEmpty()) {
                if (textBuf.isNotEmpty() && !Character.isWhitespace(textBuf[textBuf.length - 1])) {
                    textBuf.append(' ')
                }
                val start = textBuf.length
                textBuf.append(value)
                if (nodeId.isNotBlank()) {
                    anchorsBuf.add(MathSpeechAnchor(start, textBuf.length, listOf(nodeId)))
                }
            }
        }

        fun identify(element: Element): String {
            val existing = element.id()
            return if (existing.isNotBlank()) existing
            else {
                val id = "$idPrefix-$nextId"
                nextId += 1
                element.attr("id", id)
                id
            }
        }

        fun structureToken(raw: String): String {
            val t = raw.trim()
            return if (t.isEmpty() || t == ".") ""
            else tokens[t] ?: if (t.startsWith("\\")) "" else t
        }

        fun speak(element: Element) {
            when (element.normalName()) {
                "annotation", "annotation-xml", "mspace", "malignmark", "maligngroup", "mphantom" -> {}
                "semantics" -> {
                    element.children()
                        .find { it.normalName() != "annotation" && it.normalName() != "annotation-xml" }
                        ?.let { speak(it) }
                }
                "math", "mrow", "mstyle", "mpadded", "menclose", "mtd", "mtr", "mlabeledtr" -> {
                    element.children().forEach { speak(it) }
                }
                "mfrac" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.size >= 2) {
                        speak(kids[1])
                        word("分之", id)
                        speak(kids[0])
                    }
                }
                "msub" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.isNotEmpty()) speak(kids[0])
                    word("下标", id)
                    if (kids.size > 1) speak(kids[1])
                }
                "msup" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.isNotEmpty()) speak(kids[0])
                    word("上标", id)
                    if (kids.size > 1) speak(kids[1])
                }
                "msubsup" -> {
                    val id = identify(element)
                    val kids = element.children()
                    if (kids.isNotEmpty()) speak(kids[0])
                    word("下标", id)
                    if (kids.size > 1) speak(kids[1])
                    word("上标", id)
                    if (kids.size > 2) speak(kids[2])
                }
                "msqrt", "mroot" -> {
                    val id = identify(element)
                    word("根号", id)
                    element.children().forEach { speak(it) }
                }
                "mtable" -> {
                    val id = identify(element)
                    word("矩阵", id)
                    element.children().forEach { speak(it) }
                }
                else -> {
                    if (element.children().isEmpty()) {
                        word(structureToken(element.text()), identify(element))
                    } else {
                        element.children().forEach { speak(it) }
                    }
                }
            }
        }

        speak(math)
        return MathSpeechBundle(math.outerHtml(), textBuf.toString(), anchorsBuf, "structured")
    }

    fun toMathmlLatex(el: Element): String {
        val kids = el.children()
        return when (el.normalName()) {
            "math", "semantics" -> kids.joinToString(" ") { toMathmlLatex(it) }
            "mfrac" -> {
                val num = kids.getOrNull(0)?.let { toMathmlLatex(it) } ?: ""
                val den = kids.getOrNull(1)?.let { toMathmlLatex(it) } ?: ""
                "\\frac{$num}{$den}"
            }
            "msup" -> {
                val b = kids.getOrNull(0)?.let { toMathmlLatex(it) } ?: ""
                val sup = kids.getOrNull(1)?.let { toMathmlLatex(it) } ?: ""
                "$b^{$sup}"
            }
            "msub" -> {
                val b = kids.getOrNull(0)?.let { toMathmlLatex(it) } ?: ""
                val sub = kids.getOrNull(1)?.let { toMathmlLatex(it) } ?: ""
                "${b}_{$sub}"
            }
            "msubsup", "munderover" -> {
                val b = kids.getOrNull(0)?.let { toMathmlLatex(it) } ?: ""
                val sub = kids.getOrNull(1)?.let { toMathmlLatex(it) } ?: ""
                val sup = kids.getOrNull(2)?.let { toMathmlLatex(it) } ?: ""
                "${b}_{$sub}^{$sup}"
            }
            "msqrt" -> "\\sqrt{${kids.joinToString(" ") { toMathmlLatex(it) }}}"
            "mi", "mn", "mo", "mtext" -> el.text()
            else -> {
                val joined = kids.joinToString(" ") { toMathmlLatex(it) }
                if (joined.isNotBlank()) joined else el.text()
            }
        }
    }

    fun latexToChineseSpeech(latex: String): String {
        var s = latex
        var loop = true
        while (loop && (s.contains("\\frac") || s.contains("frac"))) {
            val replaced = s
                .replace("""\\?frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}""".toRegex(), " $2分之$1 ")
                .replace("""\\?frac\s*([^{}\s]+)\s*([^{}\s]+)""".toRegex(), " $2分之$1 ")
            if (replaced == s) loop = false
            else s = replaced
        }

        return s.replace("""(\w+)\^2""".toRegex(), "$1的平方")
            .replace("""(\w+)\^3""".toRegex(), "$1的立方")
            .replace("""\^2""".toRegex(), "的平方")
            .replace("""\^3""".toRegex(), "的立方")
            .replace("""\\sqrt\{([^}]+)\}""".toRegex(), "根号下$1")
            .replace("\\sqrt", "根号下")
            .replace("""_\{([^}]+)\}""".toRegex(), "下标$1")
            .replace("""_(\w)""".toRegex(), "下标$1")
            .replace("\\times", "乘以")
            .replace("\\cdot", "点乘")
            .replace("\\cdots", "等等")
            .replace("\\dots", "等等")
            .replace("⋯", "等等")
            .replace("…", "等等")
            .replace("\\div", "除以")
            .replace("\\pm", "正负")
            .replace("\\leq", "小于等于")
            .replace("\\le", "小于等于")
            .replace("≤", "小于等于")
            .replace("\\geq", "大于等于")
            .replace("\\ge", "大于等于")
            .replace("≥", "大于等于")
            .replace("\\neq", "不等于")
            .replace("≠", "不等于")
            .replace("\\approx", "约等于")
            .replace("≈", "约等于")
            .replace("\\infty", "无穷")
            .replace("∞", "无穷")
            .replace("\\lim", "极限")
            .replace("\\to", "趋近于")
            .replace("→", "趋近于")
            .replace("frac", "分之")
            .replace("{", "")
            .replace("}", "")
            .replace("\\", "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}

data class SpokenUtterance(
    val text: String,
    val href: String,
    val cssSelector: String,
    val isMath: Boolean,
    val hasInlineMath: Boolean,
    val mathSelector: String,
    val mathId: String,
    val speechMap: SpeechMap
)

object HtmlSpeechExtractor {

    private data class MathMark(val selector: String, val id: String)
    private data class ParentElement(val element: Element, val cssSelector: String?)
    private data class RawSegment(
        val text: String,
        val cssSelector: String,
        val isMath: Boolean,
        val hasInlineMath: Boolean,
        val mathSelector: String,
        val mathId: String,
        val speechMap: SpeechMap
    )

    fun fastCssSelector(
        element: Element,
        cache: MutableMap<Element, String>
    ): String {
        cache[element]?.let { return it }

        if (element.id().isNotEmpty()) {
            val idSelector = "#" + element.id()
            cache[element] = idSelector
            return idSelector
        }

        val tagName = element.tagName()
        val parent = element.parent()
        if (parent == null || parent is Document) {
            cache[element] = tagName
            return tagName
        }

        val parentSelector = fastCssSelector(parent, cache)
        val selector = if (parent.children().size > 1) {
            val index = element.elementSiblingIndex() + 1
            "$parentSelector > $tagName:nth-child($index)"
        } else {
            "$parentSelector > $tagName"
        }

        cache[element] = selector
        return selector
    }

    private fun splitsReading(element: Element): Boolean =
        element.isBlock && element.normalName() != "math"

    private fun hasRetainedContent(element: Element): Boolean {
        val name = element.normalName()
        return setOf("img", "audio", "video", "math", "svg").contains(name) ||
                element.getElementsByTag("math").isNotEmpty() ||
                element.getElementsByTag("img").isNotEmpty() ||
                element.getElementsByTag("audio").isNotEmpty() ||
                element.getElementsByTag("video").isNotEmpty()
    }

    private fun readingUnits(root: Element): List<Element> {
        val found = mutableListOf<Element>()
        fun walk(element: Element) {
            val children = element.children().toList()
            val blocks = children.filter { splitsReading(it) }
            val ownText = element.childNodes().any { it is TextNode && it.wholeText.trim().isNotEmpty() }
            if (blocks.isEmpty()) {
                if (element != root) found.add(element)
                else if (children.isNotEmpty()) found.addAll(children)
                else found.add(element)
            } else if (ownText && element != root) {
                found.add(element)
            } else {
                for (child in children) {
                    if (splitsReading(child)) walk(child)
                    else if (child.hasText() || hasRetainedContent(child)) found.add(child)
                }
            }
        }

        walk(root)
        return if (found.isNotEmpty()) found else listOf(root)
    }

    private fun identifyFormulas(
        roots: List<Element>,
        href: String,
        mathIndex: Map<Element, Int>,
        selectorCache: MutableMap<Element, String>,
        formulaBundles: MutableMap<String, MathSpeechBundle>
    ) {
        val mathNodes = roots.flatMap { root ->
            root.select("math, .katex, .MathJax").filter { it.parent() != null }
        }
        if (mathNodes.isEmpty()) return

        for (node in mathNodes) {
            if (node.parent() != null) {
                val mathmlElement = if (node.normalName() == "math") node
                else node.getElementsByTag("math").firstOrNull()
                val target = mathmlElement ?: node
                if (target.normalName() == "math" && target.id().trim().isEmpty()) {
                    val idx = mathIndex[target] ?: 0
                    target.attr("id", "vox-math-$idx")
                }

                val cssSelector = fastCssSelector(target, selectorCache)
                val localId = target.id()
                val scope = SpeechAnchors.scope(href, localId)

                val bundleOpt = mathmlElement?.let { MathSpeechMarkup.structure(it.outerHtml(), "voxfb-$localId") }
                var spokenText = bundleOpt?.spokenText?.trim() ?: ""
                if (spokenText.isEmpty()) {
                    val annotation = node.getElementsByTag("annotation").firstOrNull()
                    val latex = annotation?.text()?.takeIf { it.isNotBlank() } ?: MathSpeechMarkup.toMathmlLatex(target)
                    spokenText = MathSpeechMarkup.latexToChineseSpeech(latex)
                }

                bundleOpt?.let { b ->
                    formulaBundles[scope] = b.copy(spokenText = if (b.spokenText.isNotBlank()) b.spokenText else spokenText)
                }

                if (spokenText.isNotEmpty() && node.parent() != null) {
                    val replacement = Element("span")
                        .attr("data-math-selector", cssSelector)
                        .attr("data-math-id", scope)
                        .text(" $spokenText ")
                    node.replaceWith(replacement)
                }
            }
        }
    }

    fun extractUtterances(html: String, href: String): List<SpokenUtterance> {
        val document = Jsoup.parse(html)
        val body = document.body()

        val selectorCache = mutableMapOf<Element, String>()
        val formulaBundles = mutableMapOf<String, MathSpeechBundle>()
        val mathIndex = body.getElementsByTag("math").mapIndexed { idx, el -> el to idx }.toMap()
        val units = readingUnits(body)

        identifyFormulas(units, href, mathIndex, selectorCache, formulaBundles)

        val rawSegments = mutableListOf<RawSegment>()
        val segmentsAcc = mutableListOf<RawSegment>()
        val textAcc = java.lang.StringBuilder()
        val speechSpans = mutableListOf<SpeechMap.Span>()
        val breadcrumbs = mutableListOf<ParentElement>()
        val segmentMath = mutableListOf<MathMark>()
        var segmentHasProse = false

        fun lastCharIsWhitespace(): Boolean =
            textAcc.isNotEmpty() && textAcc[textAcc.length - 1] == ' '

        fun textAnchor(node: TextNode): Pair<String, Int>? {
            val parent = node.parent() as? Element ?: return null
            val selector = try {
                fastCssSelector(parent, selectorCache)
            } catch (_: Throwable) {
                null
            } ?: return null

            var index = 0
            var foundIdx = 0
            var matched = false
            for (child in parent.childNodes()) {
                if (matched) break
                if (child === node) {
                    foundIdx = index
                    matched = true
                } else if (child is TextNode) {
                    index += 1
                }
            }
            return selector to foundIdx
        }

        fun noteTextOrigin(node: TextNode): MathMark? {
            var p = node.parent()
            while (p != null) {
                if (p is Element && p.hasAttr("data-math-selector")) {
                    val selector = p.attr("data-math-selector")
                    val mark = MathMark(selector, p.attr("data-math-id"))
                    if (selector.isNotBlank() && segmentMath.none { it.selector == selector }) {
                        segmentMath.add(mark)
                    }
                    return mark
                }
                p = p.parent()
            }
            segmentHasProse = true
            return null
        }

        fun addSpeech(
            raw: String,
            mathId: String?,
            mathSelector: String?,
            textSelector: String?,
            nodeIndex: Int
        ) {
            val normalized = SpeechMap.normalize(raw, stripLeading = lastCharIsWhitespace())
            val start = textAcc.length
            textAcc.append(normalized.text)
            if (normalized.text.isEmpty()) return
            val end = textAcc.length

            if (mathId != null) {
                val bundle = formulaBundles[mathId]
                val nodes = bundle?.let { MathSpeechMarkup.place(normalized.text, start, it.spokenText, it.anchors) }
                    ?: emptyList()
                speechSpans.add(
                    SpeechMap.Span(
                        start = start,
                        end = end,
                        kind = SpeechMap.Kind.Math,
                        selector = mathSelector ?: "",
                        mathId = mathId,
                        nodes = nodes,
                        mathml = bundle?.canonicalMathMl ?: ""
                    )
                )
            } else {
                if (textSelector == null || normalized.pieces.isEmpty()) return
                val rawStart = normalized.pieces.first().rawStart
                val rawEnd = normalized.pieces.last().rawEnd
                speechSpans.add(
                    SpeechMap.Span(
                        start = start,
                        end = end,
                        kind = SpeechMap.Kind.Text,
                        selector = textSelector,
                        node = nodeIndex,
                        from = rawStart,
                        to = rawEnd,
                        raw = raw.substring(rawStart, rawEnd)
                    )
                )
            }
        }

        fun spokenSegmentText(accumulated: String, isFirst: Boolean): Triple<String, Int, Int> {
            if (!isFirst) return Triple(accumulated, 0, accumulated.length)
            val coreStart = accumulated.indexOfFirst { !it.isWhitespace() }
            if (coreStart < 0) return Triple("", 0, 0)
            val coreEnd = accumulated.indexOfLast { !it.isWhitespace() } + 1
            val keepEnd = if (accumulated.last().isWhitespace()) min(accumulated.length, coreEnd + 1) else coreEnd
            return Triple(accumulated.substring(coreStart, keepEnd), coreStart, keepEnd)
        }

        fun flushSegment() {
            val mathMarks = segmentMath.toList()
            val hasProse = segmentHasProse
            val built = textAcc.toString()
            val pendingSpans = speechSpans.toList()
            segmentMath.clear()
            segmentHasProse = false
            speechSpans.clear()
            textAcc.setLength(0)

            if (built.isNotBlank()) {
                val (text, keptStart, keptEnd) = spokenSegmentText(built, isFirst = segmentsAcc.isEmpty())
                if (text.isNotEmpty() && keptEnd > keptStart) {
                    val parent = breadcrumbs.lastOrNull()
                    val soleMath = if (mathMarks.size == 1 && !hasProse) mathMarks.firstOrNull() else null
                    val isMath = soleMath != null
                    val hasInlineMath = soleMath == null && mathMarks.isNotEmpty()
                    val cssSelector = soleMath?.selector ?: parent?.cssSelector ?: ""
                    val mathSelector = soleMath?.selector ?: ""
                    val mathId = soleMath?.id ?: ""
                    val map = SpeechMap(pendingSpans).slice(keptStart, keptEnd)

                    segmentsAcc.add(
                        RawSegment(
                            text = text,
                            cssSelector = cssSelector,
                            isMath = isMath,
                            hasInlineMath = hasInlineMath,
                            mathSelector = mathSelector,
                            mathId = mathId,
                            speechMap = map
                        )
                    )
                }
            }
        }

        fun flushText() {
            flushSegment()
            if (segmentsAcc.isNotEmpty()) {
                val last = segmentsAcc.last()
                val trimmed = last.text.replace("\\s+$".toRegex(), "")
                val updatedLast = if (trimmed.length == last.text.length) last
                else last.copy(text = trimmed, speechMap = last.speechMap.slice(0, trimmed.length))
                segmentsAcc[segmentsAcc.size - 1] = updatedLast
                rawSegments.addAll(segmentsAcc)
                segmentsAcc.clear()
            }
        }

        val visitor = object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element) {
                    val sel = try {
                        fastCssSelector(node, selectorCache)
                    } catch (_: Throwable) {
                        null
                    }
                    val parent = ParentElement(node, sel)
                    if (node.isBlock) {
                        flushText()
                        breadcrumbs.add(parent)
                    }
                    val tag = node.normalName()
                    if (tag == "br" || tag == "img" || tag == "audio" || tag == "video" || node.isBlock) {
                        flushText()
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> {
                        if (node.wholeText.trim().isNotEmpty()) {
                            val text = Parser.unescapeEntities(node.wholeText, false)
                            val math = noteTextOrigin(node)
                            if (math != null) {
                                addSpeech(text, math.id, math.selector, null, 0)
                            } else {
                                val anchor = textAnchor(node)
                                addSpeech(text, null, null, anchor?.first, anchor?.second ?: 0)
                            }
                        }
                    }
                    is Element -> {
                        if (node.isBlock && breadcrumbs.isNotEmpty()) {
                            flushText()
                            breadcrumbs.removeAt(breadcrumbs.size - 1)
                        }
                    }
                }
            }
        }

        for (unit in units) {
            NodeTraversor.traverse(visitor, unit)
        }
        flushText()

        val utterances = mutableListOf<SpokenUtterance>()
        for (seg in rawSegments) {
            val sentenceRanges = tokenizeSentences(seg.text)
            for ((sStart, sEnd) in sentenceRanges) {
                val subText = seg.text.substring(sStart, sEnd)
                if (subText.any { it.isLetterOrDigit() }) {
                    val slicedMap = seg.speechMap.slice(sStart, sEnd)
                    utterances.add(
                        SpokenUtterance(
                            text = subText,
                            href = href,
                            cssSelector = seg.cssSelector,
                            isMath = seg.isMath,
                            hasInlineMath = seg.hasInlineMath,
                            mathSelector = seg.mathSelector,
                            mathId = seg.mathId,
                            speechMap = slicedMap
                        )
                    )
                }
            }
        }

        return utterances
    }

    private fun tokenizeSentences(data: String): List<Pair<Int, Int>> {
        val iterator = BreakIterator.getSentenceInstance(Locale.CHINA)
        iterator.setText(data)
        var start = iterator.first()
        var end = iterator.next()
        val ranges = mutableListOf<Pair<Int, Int>>()
        while (end != BreakIterator.DONE) {
            val token = data.substring(start, end)
            val trimmed = token.replace("\\s+$".toRegex(), "")
            if (trimmed.any { it.isLetterOrDigit() }) {
                val leadWs = token.indexOfFirst { !it.isWhitespace() }
                val actualStart = if (leadWs > 0) start + leadWs else start
                val actualEnd = start + trimmed.length
                ranges.add(actualStart to actualEnd)
            }
            start = end
            end = iterator.next()
        }
        return ranges
    }
}
