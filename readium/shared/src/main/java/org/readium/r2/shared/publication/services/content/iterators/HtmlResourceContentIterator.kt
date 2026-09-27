/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(InternalReadiumApi::class, ExperimentalReadiumApi::class)

package org.readium.r2.shared.publication.services.content.iterators

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.services.content.MathSpeechBundle
import org.readium.r2.shared.publication.services.content.MathSpeechMarkup
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.extensions.tryOrNull
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.PublicationServicesHolder
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.Content.Attribute
import org.readium.r2.shared.publication.services.content.SpeechAnchors
import org.readium.r2.shared.publication.services.content.SpeechMap
import org.readium.r2.shared.publication.services.content.Content.AttributeKey
import org.readium.r2.shared.publication.services.content.Content.AudioElement
import org.readium.r2.shared.publication.services.content.Content.ImageElement
import org.readium.r2.shared.publication.services.content.Content.TextElement
import org.readium.r2.shared.publication.services.content.Content.VideoElement
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.decodeString
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.toDebugDescription
import org.readium.r2.shared.util.use
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
/**
 * Iterates an HTML [resource], starting from the given [locator].
 *
 * If you want to start mid-resource, the [locator] must contain a `cssSelector` key in its
 * [Locator.Locations] object.
 *
 * If you want to start from the end of the resource, the [locator] must have a `progression` of 1.0.
 *
 * Locators will contain a `before` context of up to `beforeMaxLength` characters.
 */
@ExperimentalReadiumApi
public class HtmlResourceContentIterator internal constructor(
    private val resource: Resource,
    private val totalProgressionRange: ClosedRange<Double>?,
    private val locator: Locator,
    private val beforeMaxLength: Int = 50,
    private val mathEngine: MathSpeechEngine? = null,
    private val publicationLanguage: String? = null,
) : Content.Iterator {

    public class Factory : ResourceContentIteratorFactory {
        override suspend fun create(
            manifest: Manifest,
            servicesHolder: PublicationServicesHolder,
            readingOrderIndex: Int,
            resource: Resource,
            mediaType: MediaType,
            locator: Locator,
        ): Content.Iterator? {
            if (!mediaType.matchesAny(MediaType.HTML, MediaType.XHTML)) {
                return null
            }

            val positions = tryOrNull { servicesHolder.positionsByReadingOrder() }
            val totalProgressionRange = positions?.getOrNull(readingOrderIndex)
                ?.firstOrNull()?.locations?.totalProgression
                ?.let { start ->
                    val end = positions.getOrNull(readingOrderIndex + 1)
                        ?.firstOrNull()?.locations?.totalProgression ?: 1.0
                    start..end
                }

            // 🌟 自动兜底获取全局 Context，防止外部无参调用时 mathEngine 为 null
            // ✅ 修改后：
            val appContext = tryOrNull {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
            }

            //android.util.Log.d("MathCAT_DEBUG", "Step 1: appContext 获取结果 = ${appContext != null}")

            val engine = appContext?.let { MathSpeechEngine.getInstance(it) }

            //android.util.Log.d("MathCAT_DEBUG", "Step 1: MathSpeechEngine 实例 = ${engine != null}")

            return HtmlResourceContentIterator(
                resource = resource,
                totalProgressionRange = totalProgressionRange,
                locator = locator,
                mathEngine = engine,
                publicationLanguage = manifest.metadata.language?.code,
            )
        }
    }

private companion object {
        // 显式指定 private 和明确的类型，满足 Explicit API 模式
        private val mathSpeechCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

        /** Blocks kept behind the reading position so the previous sentence can still move back. */
        private const val BLOCKS_BEFORE: Int = 2

        /** Blocks identified ahead of the reading position. The next window is loaded when reading reaches it. */
        private const val BLOCKS_AFTER: Int = 8

private fun fastCssSelector(
        element: org.jsoup.nodes.Element,
        cache: MutableMap<org.jsoup.nodes.Element, String>
    ): String {
        cache[element]?.let { return it }

        if (element.id().isNotEmpty()) {
            val idSelector = "#" + element.id()
            cache[element] = idSelector
            return idSelector
        }

        val tagName = element.tagName()
        val parent = element.parent()

        if (parent == null || parent is org.jsoup.nodes.Document) {
            cache[element] = tagName
            return tagName
        }

        val parentSelector = fastCssSelector(parent, cache)

        // 🌟 此处去掉 size() 的小括号，改为 parent.children().size
        val selector = if (parent.children().size > 1) {
            val index = element.elementSiblingIndex() + 1
            "$parentSelector > $tagName:nth-child($index)"
        } else {
            "$parentSelector > $tagName"
        }

        cache[element] = selector
        return selector
    }

    }

    /**
     * [Content.Element] loaded with [hasPrevious] or [hasNext], associated with the move delta.
     */
    private data class ElementWithDelta(
        val element: Content.Element,
        val delta: Int,
    )

    private var currentElement: ElementWithDelta? = null

    //private val mathSpeechCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun hasPrevious(): Boolean {
        if (currentElement?.delta == -1) return true

        ensureChapter()
        if ((currentIndex ?: readStart) - 1 < 0) {
            extendBackward()
        }
        val index = (currentIndex ?: readStart) - 1
        val content = builtElements.getOrNull(index) ?: return false

        currentIndex = index
        currentElement = ElementWithDelta(content, -1)
        return true
    }

    override fun previous(): Content.Element =
        currentElement
            ?.takeIf { it.delta == -1 }?.element
            ?.also { currentElement = null }
            ?: throw IllegalStateException(
                "Called previous() without a successful call to hasPrevious() first"
            )

    override suspend fun hasNext(): Boolean {
        if (currentElement?.delta == +1) return true

        ensureChapter()
        val index = (currentIndex ?: (readStart - 1)) + 1
        while (index >= builtElements.size && parsedUntilUnit < units.size) {
            extendForward()
        }
        val content = builtElements.getOrNull(index) ?: return false

        currentIndex = index
        currentElement = ElementWithDelta(content, +1)
        return true
    }

    override fun next(): Content.Element =
        currentElement
            ?.takeIf { it.delta == +1 }?.element
            ?.also { currentElement = null }
            ?: throw IllegalStateException(
                "Called next() without a successful call to hasNext() first"
            )

    private var currentIndex: Int? = null

    private var chapterReady: Boolean = false
    private var chapterDocument: org.jsoup.nodes.Document? = null
    private var selectorCache: MutableMap<Element, String> = HashMap(2048)
    private var units: List<Element> = emptyList()
    private var mathIndex: Map<Element, Int> = emptyMap()
    private var forwardParser: ContentParser? = null
    private var parsedFromUnit: Int = 0
    private var parsedUntilUnit: Int = 0
    private var lastUnitCounts: List<Int> = emptyList()
    private val builtElements = mutableListOf<Content.Element>()
    private var readStart: Int = 0
    private var documentLanguageCode: String? = null

    /** Formulas given a chapter-scoped id so far. The rest of the chapter waits until reading reaches them. */
    internal var identifiedFormulaCount: Int = 0
    private val formulaBundles = HashMap<String, MathSpeechBundle>()

    private suspend fun ensureChapter() {
        if (chapterReady) return
        chapterReady = true
        withContext(Dispatchers.Default) {
            val document = resource.use { res ->
                val html = res
                    .read()
                    .flatMap { it.decodeString() }
                    .getOrElse {
                        val error = DebugError("Failed to read HTML resource", it.cause)
                        Timber.w(error.toDebugDescription())
                        return@withContext
                    }
                Jsoup.parse(html)
            }
            chapterDocument = document
            documentLanguageCode = documentLanguage(document)
            val body = document.body()
            mathIndex = body.getElementsByTag("math").mapIndexed { index, math -> math to index }.toMap()
            units = readingUnits(body)
            val anchor = anchorUnit(document)
            val from = (anchor - BLOCKS_BEFORE).coerceAtLeast(0)
            val to = (anchor + BLOCKS_AFTER + 1).coerceAtMost(units.size)
            forwardParser = ContentParser(
                baseLocator = locator,
                startElement = locator.locations.cssSelector?.let {
                    tryOrNull { document.selectFirst(it) }
                },
                beforeMaxLength = beforeMaxLength,
                selectorCache = selectorCache,
                formulaBundles = formulaBundles,
            )
            val produced = parseUnitRange(from, to, forwardParser!!)
            builtElements += produced
            parsedFromUnit = from
            parsedUntilUnit = to
            if (parsedFromUnit == 0 && parsedUntilUnit == units.size) {
                assignExactProgression()
            }
            val parserStart = forwardParser!!.readingStart()
            val progression = locator.locations.progression
            readStart = when {
                progression == 1.0 && locator.locations.cssSelector == null ->
                    builtElements.size
                locator.locations.cssSelector != null ->
                    parserStart
                progression != null && parsedFromUnit == 0 && parsedUntilUnit == units.size -> {
                    val prog = progression.coerceIn(0.0, 1.0)
                    (prog * builtElements.size).toInt()
                        .coerceIn(0, (builtElements.size - 1).coerceAtLeast(0))
                }
                progression != null -> {
                    val local = (anchor - from).coerceIn(0, lastUnitCounts.size)
                    lastUnitCounts.take(local).sum()
                }
                else ->
                    0
            }
        }
    }

    private suspend fun extendForward() {
        if (parsedUntilUnit >= units.size) return
        val parser = forwardParser ?: return
        val from = parsedUntilUnit
        val to = (from + BLOCKS_AFTER).coerceAtMost(units.size)
        builtElements += parseUnitRange(from, to, parser)
        parsedUntilUnit = to
        if (parsedFromUnit == 0 && parsedUntilUnit == units.size) {
            assignExactProgression()
        }
    }

    private suspend fun extendBackward() {
        if (parsedFromUnit <= 0) return
        val to = parsedFromUnit
        val from = (to - BLOCKS_BEFORE).coerceAtLeast(0)
        if (from >= to) return
        val parser = ContentParser(
            baseLocator = locator,
            startElement = null,
            beforeMaxLength = beforeMaxLength,
            selectorCache = selectorCache,
            formulaBundles = formulaBundles,
        )
        val produced = parseUnitRange(from, to, parser)
        if (produced.isEmpty()) {
            parsedFromUnit = from
            return
        }
        builtElements.addAll(0, produced)
        val added = produced.size
        readStart += added
        currentIndex = currentIndex?.plus(added)
        parsedFromUnit = from
        if (parsedFromUnit == 0 && parsedUntilUnit == units.size) {
            assignExactProgression()
        }
    }

    private suspend fun parseUnitRange(
        from: Int,
        to: Int,
        parser: ContentParser,
    ): List<Content.Element> {
        if (from >= to) return emptyList()
        val language = documentLanguageCode
        val before = parser.capturedCount()
        identifyFormulas(units.subList(from, to), language)
        val counts = mutableListOf<Int>()
        for (index in from until to) {
            val countBefore = parser.capturedCount()
            NodeTraversor.traverse(parser, units[index])
            counts.add(parser.capturedCount() - countBefore)
        }
        lastUnitCounts = counts
        val fresh = parser.captured().drop(before)
        val origin = from
        val denominator = units.size.coerceAtLeast(1)
        return fresh.mapIndexed { offset, element ->
            element.withProgression((origin + offset).toDouble() / denominator)
        }
    }

    /**
     * Gives chapter-scoped ids only to formulas inside [roots].
     * The index is still the formula's position in the whole chapter, so it matches the page.
     */
    private suspend fun identifyFormulas(
        roots: List<Element>,
        documentLanguage: String?,
    ) {
        val mathNodes = roots.flatMap { root ->
            root.select("math, .katex, .MathJax").filter { it.parent() != null }
        }
        if (mathNodes.isEmpty()) return
        identifiedFormulaCount += mathNodes.size

        val converted = coroutineScope {
            mathNodes.map { node ->
                val mathmlElement = if (node.normalName() == "math") {
                    node
                } else {
                    node.getElementsByTag("math").firstOrNull()
                }
                val target = mathmlElement ?: node
                if (target.normalName() == "math" && target.id().isBlank()) {
                    val index = mathIndex[target] ?: 0
                    target.attr("id", "vox-math-$index")
                }
                val cssSelector = fastCssSelector(target, selectorCache)
                val scope = SpeechAnchors.scope(locator.href.toString(), target.id())
                async(Dispatchers.Default) {
                    Triple(node, convertMathToSpeech(node, documentLanguage, scope), cssSelector)
                }
            }.awaitAll()
        }
        for ((node, spoken, cssSelector) in converted) {
            val spokenText = spoken.text
            if (spoken.bundle != null) formulaBundles[spoken.scope] = spoken.bundle
            if (spokenText.isBlank() || node.parent() == null) continue
            val mathmlElement = if (node.normalName() == "math") {
                node
            } else {
                node.getElementsByTag("math").firstOrNull()
            }
            val localId = (mathmlElement ?: node).id()
            val mathId = SpeechAnchors.scope(locator.href.toString(), localId)
            val replacement = org.jsoup.nodes.Element("span")
                .attr("data-math-selector", cssSelector)
                .attr("data-math-id", mathId)
                .text(" $spokenText ")
            node.replaceWith(replacement)
        }
    }

    private fun assignExactProgression() {
        val total = builtElements.size.coerceAtLeast(1)
        for (index in builtElements.indices) {
            builtElements[index] = builtElements[index].withProgression(index.toDouble() / total)
        }
    }

    private fun Content.Element.withProgression(progression: Double): Content.Element =
        copy(
            progression = progression,
            totalProgression = totalProgressionRange?.let {
                it.start + progression * (it.endInclusive - it.start)
            }
        )

    private fun anchorUnit(document: org.jsoup.nodes.Document): Int {
        if (units.isEmpty()) return 0
        locator.locations.cssSelector?.let { selector ->
            val node = tryOrNull { document.selectFirst(selector) }
            if (node != null) {
                val match = units.indexOfLast { it == node || containsNode(it, node) }
                if (match >= 0) return match
            }
        }
        val progression = locator.locations.progression
        if (progression != null) {
            if (progression >= 1.0) return units.lastIndex
            return (progression.coerceIn(0.0, 1.0) * units.size).toInt()
                .coerceIn(0, units.lastIndex)
        }
        return 0
    }

    private fun containsNode(container: Element, node: Element): Boolean {
        var current: org.jsoup.nodes.Node? = node
        while (current != null) {
            if (current == container) return true
            current = current.parent()
        }
        return false
    }

    private fun splitsReading(element: Element): Boolean =
        element.isBlock && element.normalName() != "math"

    private fun readingUnits(root: Element): List<Element> {
        val found = mutableListOf<Element>()
        fun walk(element: Element) {
            val children = element.children()
            val blocks = children.filter { splitsReading(it) }
            val ownText = element.childNodes().any { node ->
                node is TextNode && node.wholeText.isNotBlank()
            }
            if (blocks.isEmpty()) {
                if (element != root) {
                    found.add(element)
                } else if (children.isNotEmpty()) {
                    found.addAll(children)
                } else {
                    found.add(element)
                }
                return
            }
            // Text before and after a nested block belongs to this element.
            // Keep it together so those words are not dropped.
            if (ownText && element != root) {
                found.add(element)
                return
            }
            for (child in children) {
                if (splitsReading(child)) {
                    walk(child)
                } else if (child.hasText() || hasRetainedContent(child)) {
                    found.add(child)
                }
            }
        }
        walk(root)
        return found.ifEmpty { listOf(root) }
    }

    private fun hasRetainedContent(element: Element): Boolean =
        element.normalName() in setOf("img", "audio", "video", "math", "svg") ||
            element.getElementsByTag("math").isNotEmpty() ||
            element.getElementsByTag("img").isNotEmpty() ||
            element.getElementsByTag("audio").isNotEmpty() ||
            element.getElementsByTag("video").isNotEmpty()


    private fun speechLocale(node: org.jsoup.nodes.Element, documentLanguage: String?): String {
        return MathSpeechLanguage.code?.takeIf { it.isNotBlank() }
            ?: node.language?.takeIf { it.isNotBlank() }
            ?: documentLanguage?.takeIf { it.isNotBlank() }
            ?: publicationLanguage?.takeIf { it.isNotBlank() }
            ?: "en"
    }

    private fun documentLanguage(document: org.jsoup.nodes.Document): String? {
        val html = document.selectFirst("html")
        return html?.attr("xml:lang")?.takeIf { it.isNotBlank() }
            ?: html?.attr("lang")?.takeIf { it.isNotBlank() }
            ?: publicationLanguage
    }

    private data class SpokenFormula(
        val scope: String,
        val text: String,
        val bundle: MathSpeechBundle?,
    )

    private suspend fun convertMathToSpeech(
        node: org.jsoup.nodes.Element,
        documentLanguage: String?,
        scope: String,
    ): SpokenFormula {
        return try {
            val mathmlElement = if (node.normalName() == "math") node else node.getElementsByTag("math").firstOrNull()
            val mathml = mathmlElement?.outerHtml() ?: node.outerHtml()
            val locale = speechLocale(mathmlElement ?: node, documentLanguage)
            val cacheKey = locale + "\u0000" + mathml

            val cached: String? = mathSpeechCache[cacheKey]
            if (!cached.isNullOrBlank()) {
                val bundle = if (mathmlElement != null) {
                    mathEngine?.toBundle(mathml, locale, scope)
                } else {
                    null
                }
                return SpokenFormula(scope, bundle?.spokenText?.ifBlank { null } ?: cached, bundle)
            }

            var spokenText = ""
            var bundle: MathSpeechBundle? = null

            if (mathmlElement != null && mathEngine != null) {
                try {
                    bundle = mathEngine.toBundle(mathml, locale = locale, scopeId = scope)
                    spokenText = bundle?.spokenText.orEmpty()
                } catch (t: Throwable) {
                    Timber.w(t, "MathCAT toSpeech 失败")
                }
            }

            if (spokenText.isBlank()) {
                bundle = null
                try {
                    val annotation = node.getElementsByTag("annotation").firstOrNull()
                    val latex = annotation?.text() ?: node.toMathmlLatex()
                    spokenText = if (locale.startsWith("zh", ignoreCase = true)) {
                        latexToChineseSpeech(latex)
                    } else {
                        latexToPlainSpeech(latex)
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "LaTeX 降级失败")
                }
            }

            if (spokenText.isNotBlank()) {
                mathSpeechCache[cacheKey] = spokenText
            }
            SpokenFormula(scope, spokenText, bundle)
        } catch (t: Throwable) {
            SpokenFormula(scope, "", null)
        }
    }



    /**
     * Warms speech for the start of the next chapter only, not every formula in it.
     */
    internal fun preloadNextResourceMath(nextResource: Resource, scope: CoroutineScope): Unit {
        scope.launch(Dispatchers.IO) {
            try {
                val html = nextResource.read()
                    .flatMap { it.decodeString() }
                    .getOrNull() ?: return@launch

                val doc = Jsoup.parse(html)
                val mathNodes = doc.body().select("math, .katex, .MathJax")
                    .take(BLOCKS_BEFORE + BLOCKS_AFTER + 1)
                if (mathNodes.isEmpty()) return@launch
                val language = documentLanguage(doc)

                mathNodes.map { node ->
                    async(Dispatchers.Default) {
                        val localId = node.id().ifBlank { "preload" }
                        convertMathToSpeech(node, language, SpeechAnchors.scope(locator.href.toString(), localId))
                    }
                }.awaitAll()

                Timber.d("下一章开头公式预热完毕，共缓存 ${mathNodes.size} 个公式")
            } catch (e: Throwable) {
                Timber.w(e, "预热下一章公式失败")
            }
        }
    }

    private fun Content.Element.copy(progression: Double?, totalProgression: Double?): Content.Element {
        fun Locator.update(): Locator =
            copyWithLocations(
                progression = progression,
                totalProgression = totalProgression
            )

        return when (this) {
            is TextElement -> copy(
                locator = locator.update(),
                segments = segments.map {
                    it.copy(locator = it.locator.update())
                }
            )
            is AudioElement -> copy(locator = locator.update())
            is VideoElement -> copy(locator = locator.update())
            is ImageElement -> copy(locator = locator.update())
            else -> this
        }
    }

    /**
     * Holds the result of parsing the HTML resource into a list of [Content.Element].
     *
     * The [startIndex] will be calculated from the element matched by the base [locator], if
     * possible. Defaults to 0.
     */
    public data class ParsedElements(
        val elements: List<Content.Element> = emptyList(),
        val startIndex: Int = 0,
    )

 private class ContentParser(
        private val baseLocator: Locator,
        private val startElement: Element?,
        private val beforeMaxLength: Int,
        private val selectorCache: MutableMap<Element, String> = HashMap(),
        private val formulaBundles: Map<String, MathSpeechBundle> = emptyMap(),
    ) : NodeVisitor {

        fun result() = ParsedElements(
            elements = elements,
            startIndex = if (baseLocator.locations.progression == 1.0) {
                elements.size
            } else {
                startIndex
            }
        )

        fun captured(): List<Content.Element> = elements.toList()

        fun capturedCount(): Int = elements.size

        fun readingStart(): Int = startIndex

        private val elements = mutableListOf<Content.Element>()
        private var startIndex = 0

        private val segmentsAcc = mutableListOf<TextElement.Segment>()
        private var textAcc = StringBuilder()
        private val speechSpans = mutableListOf<SpeechMap.Span>()
        private var wholeRawTextAcc: String? = null
        private var elementRawTextAcc: String = ""
        private var rawTextAcc: String = ""
        private var currentLanguage: String? = null
        private val breadcrumbs = mutableListOf<ParentElement>()
        private val segmentMath = mutableListOf<MathMark>()
        private var segmentHasProse = false

        private data class MathMark(val selector: String, val id: String)

        private data class ParentElement(
            val element: Element,
            val cssSelector: String?,
        ) {
            constructor(element: Element, cache: MutableMap<Element, String>) : this(
                element = element,
                // 安全调用，绝不抛异常
                cssSelector = tryOrNull { fastCssSelector(element, cache) }
            )
        }

        @OptIn(DelicateReadiumApi::class)
        override fun head(node: Node, depth: Int) {
            if (node is Element) {
                // 🛡️ 保持原生逻辑：永远不为 null，彻底杜绝 NPE 崩溃
                val parent = ParentElement(node, selectorCache)
                if (node.isBlock) {
                    flushText()
                    breadcrumbs.add(parent)
                }

                val tag = node.normalName()

                val elementLocator: Locator by lazy {
                    baseLocator.copy(
                        locations = Locator.Locations(
                            otherLocations = buildMap {
                                parent.cssSelector?.let {
                                    put("cssSelector", it as Any)
                                }
                            }
                        )
                    )
                }

                when {
                    tag == "br" -> {
                        flushText()
                    }

                    tag == "img" -> {
                        flushText()

                        node.srcRelativeToHref(baseLocator.href)?.let { url ->
                            elements.add(
                                ImageElement(
                                    locator = elementLocator,
                                    embeddedLink = Link(href = url),
                                    caption = null,
                                    attributes = buildList {
                                        val alt = node.attr("alt").takeIf { it.isNotBlank() }
                                        if (alt != null) {
                                            add(Attribute(AttributeKey.ACCESSIBILITY_LABEL, alt))
                                        }
                                    }
                                )
                            )
                        }
                    }

                    tag == "audio" || tag == "video" -> {
                        flushText()

                        val url = node.srcRelativeToHref(baseLocator.href)
                        val link: Link? =
                            if (url != null) {
                                Link(href = url)
                            } else {
                                val sources = node.select("source")
                                    .mapNotNull { source ->
                                        source.srcRelativeToHref(baseLocator.href)?.let { url ->
                                            Link(
                                                href = url,
                                                mediaType = MediaType(source.attr("type"))
                                            )
                                        }
                                    }

                                sources.firstOrNull()?.copy(alternates = sources.drop(1))
                            }

                        if (link != null) {
                            when (tag) {
                                "audio" -> elements.add(
                                    AudioElement(
                                        locator = elementLocator,
                                        embeddedLink = link,
                                        attributes = emptyList()
                                    )
                                )
                                "video" -> elements.add(
                                    VideoElement(
                                        locator = elementLocator,
                                        embeddedLink = link,
                                        attributes = emptyList()
                                    )
                                )
                                else -> {}
                            }
                        }
                    }

                    node.isBlock -> {
                        flushText()
                    }
                }
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is TextNode && node.wholeText.isNotBlank()) {
                val language = node.language
                if (currentLanguage != language) {
                    flushSegment()
                    currentLanguage = language
                }

                val text = Parser.unescapeEntities(node.wholeText, false)
                rawTextAcc += text
                val math = noteTextOrigin(node)
                if (math != null) {
                    addSpeech(text, mathId = math.id, mathSelector = math.selector, textSelector = null, nodeIndex = 0)
                } else {
                    val anchor = textAnchor(node)
                    addSpeech(
                        text,
                        mathId = null,
                        mathSelector = null,
                        textSelector = anchor?.first,
                        nodeIndex = anchor?.second ?: 0,
                    )
                }
            } else if (node is Element) {
                if (node.isBlock) {
                    // 🛡️ 防御检查，防止 List 为空时崩溃
                    if (breadcrumbs.isNotEmpty()) {
                        flushText()
                        breadcrumbs.removeAt(breadcrumbs.lastIndex)
                    }
                }
            }
        }

        private fun addSpeech(
            raw: String,
            mathId: String?,
            mathSelector: String?,
            textSelector: String?,
            nodeIndex: Int,
        ) {
            val normalized = SpeechMap.normalize(raw, stripLeading = lastCharIsWhitespace())
            val start = textAcc.length
            textAcc.append(normalized.text)
            if (normalized.text.isEmpty()) return
            val end = textAcc.length
            if (mathId != null) {
                val bundle = formulaBundles[mathId]
                val nodes = if (bundle == null) {
                    emptyList()
                } else {
                    MathSpeechMarkup.place(normalized.text, start, bundle.spokenText, bundle.anchors)
                }
                speechSpans += SpeechMap.Span(
                    start = start,
                    end = end,
                    kind = SpeechMap.Kind.Math,
                    selector = mathSelector.orEmpty(),
                    mathId = mathId,
                    nodes = nodes,
                    mathml = bundle?.canonicalMathMl.orEmpty(),
                )
                return
            }
            if (textSelector == null || normalized.pieces.isEmpty()) return
            val rawStart = normalized.pieces.first().rawStart
            val rawEnd = normalized.pieces.last().rawEnd
            speechSpans += SpeechMap.Span(
                start = start,
                end = end,
                kind = SpeechMap.Kind.Text,
                selector = textSelector,
                node = nodeIndex,
                from = rawStart,
                to = rawEnd,
                raw = raw.substring(rawStart, rawEnd),
            )
        }

        private fun lastCharIsWhitespace(): Boolean =
            textAcc.lastOrNull() == ' '

        private fun textAnchor(node: TextNode): Pair<String, Int>? {
            val element = node.parent() as? Element ?: return null
            val selector = tryOrNull { fastCssSelector(element, selectorCache) } ?: return null
            var index = 0
            for (child in element.childNodes()) {
                if (child === node) return selector to index
                if (child is TextNode) index++
            }
            return selector to index
        }

        private fun flushText() {
            flushSegment()

            val parent = breadcrumbs.lastOrNull()

            if (startIndex == 0 && startElement != null && parent?.element == startElement) {
                startIndex = elements.size
            }

            if (segmentsAcc.isEmpty()) return

            segmentsAcc[segmentsAcc.size - 1] = segmentsAcc.last().trimmedEnd()

            elements.add(
                TextElement(
                    locator = baseLocator.copy(
                        locations = Locator.Locations(
                            otherLocations = buildMap {
                                parent?.cssSelector?.let {
                                    put("cssSelector", it as Any)
                                }
                            }
                        ),
                        text = Locator.Text.trimmingText(
                            elementRawTextAcc,
                            before = segmentsAcc.firstOrNull()?.locator?.text?.before
                        )
                    ),
                    role = TextElement.Role.Body,
                    segments = segmentsAcc.toList()
                )
            )
            elementRawTextAcc = ""
            segmentsAcc.clear()
        }

        private fun noteTextOrigin(node: TextNode): MathMark? {
            var parent = node.parent()
            while (parent != null) {
                if (parent is Element && parent.hasAttr("data-math-selector")) {
                    val selector = parent.attr("data-math-selector")
                    val mark = MathMark(selector, parent.attr("data-math-id"))
                    if (selector.isNotBlank() && segmentMath.none { it.selector == selector }) {
                        segmentMath.add(mark)
                    }
                    return mark
                }
                parent = parent.parent()
            }
            segmentHasProse = true
            return null
        }

        private fun locatorLocations(
            parent: ParentElement?,
            mathMarks: List<MathMark>,
            hasProse: Boolean,
        ): Map<String, Any> = buildMap {
            val soleMath = mathMarks.singleOrNull()
            if (soleMath != null && !hasProse) {
                put("isMath", true)
                put("cssSelector", soleMath.selector)
                put("mathSelector", soleMath.selector)
                if (soleMath.id.isNotBlank()) {
                    put("mathId", soleMath.id)
                }
            } else {
                parent?.cssSelector?.let { put("cssSelector", it) }
                if (mathMarks.isNotEmpty()) {
                    put("hasInlineMath", true)
                }
            }
        }

        private fun flushSegment() {
            val mathMarks = segmentMath.toList()
            val hasProse = segmentHasProse
            val built = textAcc.toString()
            val pendingSpans = speechSpans.toList()
            segmentMath.clear()
            segmentHasProse = false
            speechSpans.clear()

            if (built.isNotBlank()) {
                val (text, kept) = spokenSegmentText(built, isFirst = segmentsAcc.isEmpty())
                val parent = breadcrumbs.lastOrNull()
                val locations = locatorLocations(parent, mathMarks, hasProse).toMutableMap()
                val map = SpeechMap(pendingSpans).slice(kept.first, kept.last + 1)
                if (map.spans.any { it.kind == SpeechMap.Kind.Math }) {
                    locations[SpeechMap.KEY] = map.toStored()
                }

                segmentsAcc.add(
                    TextElement.Segment(
                        locator = baseLocator.copy(
                            locations = Locator.Locations(
                                otherLocations = locations
                            ),
                            text = Locator.Text.trimmingText(
                                rawTextAcc,
                                before = wholeRawTextAcc?.takeLast(beforeMaxLength)
                            )
                        ),
                        text = text,
                        attributes = buildList {
                            currentLanguage?.let {
                                add(Attribute(AttributeKey.LANGUAGE, Language(it)))
                            }
                        }
                    )
                )
            }

            if (rawTextAcc != "") {
                wholeRawTextAcc = (wholeRawTextAcc ?: "") + rawTextAcc
                elementRawTextAcc += rawTextAcc
            }
            rawTextAcc = ""
            textAcc.clear()
        }
    }
}

private fun Locator.Text.Companion.trimmingText(text: String, before: String?): Locator.Text {
    val leadingWhitespace = text.takeWhile { it.isWhitespace() }
    val trailingWhitespace = text.takeLastWhile { it.isWhitespace() }
    return Locator.Text(
        before = ((before ?: "") + leadingWhitespace).takeUnless { it.isBlank() },
        highlight = text.substring(
            leadingWhitespace.length,
            text.length - trailingWhitespace.length
        ),
        after = trailingWhitespace.takeUnless { it.isBlank() }
    )
}

private val Node.language: String? get() =
    attr("xml:lang").takeUnless { it.isBlank() }
        ?: attr("lang").takeUnless { it.isBlank() }
        ?: parent()?.language

private fun Node.srcRelativeToHref(baseUrl: Url): Url? =
    attr("src")
        .takeIf { it.isNotBlank() }
        ?.let { Url(it) }
        ?.let { baseUrl.resolve(it) }

/**
 * The first segment of a block drops leading whitespace and keeps at most one
 * trailing space. Later segments keep the accumulated text. The returned range
 * is the slice of [accumulated] that becomes [TextElement.Segment.text].
 */
private fun spokenSegmentText(accumulated: String, isFirst: Boolean): Pair<String, IntRange> {
    if (!isFirst) return accumulated to (0 until accumulated.length)
    val coreStart = accumulated.indexOfFirst { !it.isWhitespace() }
    val coreEnd = accumulated.indexOfLast { !it.isWhitespace() } + 1
    val keepEnd = if (accumulated.last().isWhitespace()) {
        (coreEnd + 1).coerceAtMost(accumulated.length)
    } else {
        coreEnd
    }
    val range = coreStart until keepEnd
    return accumulated.substring(range.first, range.last + 1) to range
}

private fun TextElement.Segment.trimmedEnd(): TextElement.Segment {
    val trimmed = text.trimEnd()
    if (trimmed.length == text.length) return this
    val map = SpeechMap.from(locator) ?: return copy(text = trimmed)
    val sliced = map.slice(0, trimmed.length)
    return copy(
        text = trimmed,
        locator = locator.copy(
            locations = locator.locations.copy(
                otherLocations = locator.locations.otherLocations + (SpeechMap.KEY to sliced.toStored())
            )
        )
    )
}
