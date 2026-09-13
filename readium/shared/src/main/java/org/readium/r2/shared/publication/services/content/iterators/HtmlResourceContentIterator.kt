/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(InternalReadiumApi::class)

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                mathEngine = engine
            )
        }
    }

private companion object {
        // 显式指定 private 和明确的类型，满足 Explicit API 模式
        private val mathSpeechCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

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

        val elements = elements()
        val index = (currentIndex ?: elements.startIndex) - 1

        val content = elements.elements.getOrNull(index)
            ?: return false

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

        val elements = elements()
        val index = (currentIndex ?: (elements.startIndex - 1)) + 1

        val content = elements.elements.getOrNull(index)
            ?: return false

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

    private suspend fun elements(): ParsedElements =
        parsedElements
            ?: parseElements().also { parsedElements = it }

    private var parsedElements: ParsedElements? = null



    private suspend fun parseElements(): ParsedElements =
        withContext(Dispatchers.Default) {
            val totalStart = System.currentTimeMillis()
            //android.util.Log.i("PERF_DEBUG", "============== 🚀 开始 parseElements ==============")

            var stepStart = System.currentTimeMillis()
            val document = resource.use { res ->
                val html = res
                    .read()
                    .flatMap { it.decodeString() }
                    .getOrElse {
                        val error = DebugError("Failed to read HTML resource", it.cause)
                        Timber.w(error.toDebugDescription())
                        return@withContext ParsedElements()
                    }

                Jsoup.parse(html)
            }
            //android.util.Log.i("PERF_DEBUG", "⏱️ [步骤1] HTML 读取与 Jsoup.parse 耗时: ${System.currentTimeMillis() - stepStart} ms")

            // 🌟 贯穿全流程的共享选择器缓存池
            val selectorCache = HashMap<org.jsoup.nodes.Element, String>(2048)

            // 1. 公式预处理（共用缓存）
            stepStart = System.currentTimeMillis()
            preprocessMathElements(document.body(), selectorCache)
            //android.util.Log.i("PERF_DEBUG", "⏱️ [步骤2] preprocessMathElements 全部公式耗时: ${System.currentTimeMillis() - stepStart} ms")

            // 2. NodeTraversor 遍历（传入共享缓存，彻底消灭 2.2 秒延迟）
            stepStart = System.currentTimeMillis()
            val contentParser = ContentParser(
                baseLocator = locator,
                startElement = locator.locations.cssSelector?.let {
                    tryOrNull { document.selectFirst(it) }
                },
                beforeMaxLength = beforeMaxLength,
                selectorCache = selectorCache // 🌟 传入共享缓存
            )
            NodeTraversor.traverse(contentParser, document.body())
            val elements = contentParser.result()
            val elementCount = elements.elements.size
            //android.util.Log.i("PERF_DEBUG", "⏱️ [步骤3] NodeTraversor 遍历耗时: ${System.currentTimeMillis() - stepStart} ms (共生成 $elementCount 个朗读节点)")

            if (elementCount == 0) {
                return@withContext elements
            }

            val adjustedStartIndex = if (elements.startIndex == 0 && locator.locations.progression != null) {
                val prog = locator.locations.progression!!.coerceIn(0.0, 1.0)
                (prog * elementCount).toInt().coerceIn(0, elementCount - 1)
            } else {
                elements.startIndex
            }

            val result = elements.copy(
                startIndex = adjustedStartIndex,
                elements = elements.elements.mapIndexed { index, element ->
                    val progression = index.toDouble() / elementCount
                    element.copy(
                        progression = progression,
                        totalProgression = totalProgressionRange?.let {
                            totalProgressionRange.start + progression * (totalProgressionRange.endInclusive - totalProgressionRange.start)
                        }
                    )
                }
            )

            //android.util.Log.i("PERF_DEBUG", "🏁 [总结] parseElements 整体返回总耗时: ${System.currentTimeMillis() - totalStart} ms")
            //android.util.Log.i("PERF_DEBUG", "==================================================")
            result
        }


    private suspend fun convertMathToSpeech(node: org.jsoup.nodes.Element, index: Int = 0): String {
        return try {
            val mathmlElement = if (node.normalName() == "math") node else node.getElementsByTag("math").firstOrNull()
            val cacheKey = mathmlElement?.outerHtml() ?: node.outerHtml()

            val cached: String? = mathSpeechCache[cacheKey]
            if (!cached.isNullOrBlank()) {
                return cached
            }

            var spokenText = ""
            val singleStart = System.currentTimeMillis()

            if (mathmlElement != null && mathEngine != null) {
                try {
                    spokenText = mathEngine.toSpeech(cacheKey, locale = "zh")
                    val cost = System.currentTimeMillis() - singleStart
                    if (cost > 100) { // 超过 100ms 的慢转换打印出来
                        //android.util.Log.w("PERF_DEBUG", "⚠️ 公式[$index] MathCAT 耗时偏长: ${cost} ms")
                    }
                } catch (t: Throwable) {
                    Timber.w(t, "MathCAT toSpeech 失败")
                }
            }

            if (spokenText.isBlank()) {
                try {
                    val annotation = node.getElementsByTag("annotation").firstOrNull()
                    val latex = annotation?.text() ?: node.toMathmlLatex()
                    spokenText = latexToChineseSpeech(latex)
                } catch (t: Throwable) {
                    Timber.w(t, "LaTeX 降级失败")
                }
            }

            if (spokenText.isNotBlank()) {
                mathSpeechCache[cacheKey] = spokenText
            }
            spokenText
        } catch (t: Throwable) {
            ""
        }
    }



    private suspend fun preprocessMathElements(
        root: org.jsoup.nodes.Element,
        selectorCache: MutableMap<org.jsoup.nodes.Element, String>
    ): Unit = withContext(Dispatchers.Default) {
        try {
            val mathNodes = root.select("math, .katex, .MathJax")
            if (mathNodes.isEmpty()) return@withContext

            data class MathTaskItem(
                val index: Int,
                val node: org.jsoup.nodes.Element,
                val cssSelector: String
            )

            val tasks = mathNodes.mapIndexed { index, node ->
                val mathmlElement = if (node.normalName() == "math") node else node.getElementsByTag("math").firstOrNull()
                val targetElement = mathmlElement ?: node
                val cssSelector = fastCssSelector(targetElement, selectorCache)
                MathTaskItem(index, node, cssSelector)
            }

            val results = tasks.map { item ->
                async(Dispatchers.Default) {
                    val spokenText = convertMathToSpeech(item.node, item.index)
                    Pair(item, spokenText)
                }
            }.awaitAll()

            for ((item, spokenText) in results) {
                if (spokenText.isNotBlank()) {
                    val parent = item.node.parent() ?: continue
                    val replacement = org.jsoup.nodes.Element("span")
                        .attr("data-math-selector", item.cssSelector)
                        .text(" $spokenText ")
                    item.node.replaceWith(replacement)
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "preprocessMathElements 异常")
        }
    }

    /**
     * 🌟 下一章节公式预热（加 internal 修饰符和显式 : Unit 返回类型，满足 API 检查）
     */
    internal fun preloadNextResourceMath(nextResource: Resource, scope: CoroutineScope): Unit {
        scope.launch(Dispatchers.IO) {
            try {
                val html = nextResource.read()
                    .flatMap { it.decodeString() }
                    .getOrNull() ?: return@launch

                val doc = Jsoup.parse(html)
                val mathNodes = doc.body().select("math, .katex, .MathJax")
                if (mathNodes.isEmpty()) return@launch

                mathNodes.map { node ->
                    async(Dispatchers.Default) {
                        convertMathToSpeech(node)
                    }
                }.awaitAll()

                Timber.d("下一章数学公式预热完毕，共缓存 ${mathNodes.size} 个公式")
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
        private val selectorCache: MutableMap<Element, String> = HashMap()
    ) : NodeVisitor {

        fun result() = ParsedElements(
            elements = elements,
            startIndex = if (baseLocator.locations.progression == 1.0) {
                elements.size
            } else {
                startIndex
            }
        )

        private val elements = mutableListOf<Content.Element>()
        private var startIndex = 0

        private val segmentsAcc = mutableListOf<TextElement.Segment>()
        private var textAcc = StringBuilder()
        private var wholeRawTextAcc: String? = null
        private var elementRawTextAcc: String = ""
        private var rawTextAcc: String = ""
        private var currentLanguage: String? = null
        private val breadcrumbs = mutableListOf<ParentElement>()

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
                appendNormalisedText(text)
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

        private fun appendNormalisedText(text: String) {
            textAcc.appendNormalisedWhitespace(text, lastCharIsWhitespace())
        }

        private fun lastCharIsWhitespace(): Boolean =
            textAcc.lastOrNull() == ' '

        private fun flushText() {
            flushSegment()

            val parent = breadcrumbs.lastOrNull()

            if (startIndex == 0 && startElement != null && parent?.element == startElement) {
                startIndex = elements.size
            }

            if (segmentsAcc.isEmpty()) return

            segmentsAcc[segmentsAcc.size - 1] = segmentsAcc.last().run { copy(text = text.trimEnd()) }

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

        private fun flushSegment() {
            var text = textAcc.toString()
            val trimmedText = text.trim()

            if (text.isNotBlank()) {
                if (segmentsAcc.isEmpty()) {
                    text = text.trimStart()

                    val whitespaceSuffix = text.lastOrNull()
                        ?.takeIf { it.isWhitespace() }
                        ?: ""

                    text = trimmedText + whitespaceSuffix
                }

                val parent = breadcrumbs.lastOrNull()

                segmentsAcc.add(
                    TextElement.Segment(
                        locator = baseLocator.copy(
                            locations = Locator.Locations(
                                otherLocations = buildMap {
                                    parent?.cssSelector?.let {
                                        put("cssSelector", it as Any)
                                    }
                                }
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
 * After normalizing the whitespace within a string, appends it to a string builder.
 *
 * Largely inspired by JSoup's `StringUtil.appendNormalisedWhitespace`.
 *
 * Note that we don't use directly JSoup's method because we need to keep the non-breaking
 * spaces in the text. Otherwise, they will be lost post-text tokenization and Hypothesis won't
 * match the results.
 *
 * @param string String to normalize whitespace within.
 * @param stripLeading Set to true if you wish to remove any leading whitespace.
 */
private fun StringBuilder.appendNormalisedWhitespace(
    string: String,
    stripLeading: Boolean,
) {
    var lastWasWhite = false
    var reachedNonWhite = false
    val len = string.length
    var c: Int
    var i = 0
    while (i < len) {
        c = string.codePointAt(i)
        if (isWhitespace(c)) {
            if ((stripLeading && !reachedNonWhite) || lastWasWhite) {
                i += Character.charCount(c)
                continue
            }
            append(' ')
            lastWasWhite = true
        } else if (!isInvisibleChar(c)) {
            appendCodePoint(c)
            lastWasWhite = false
            reachedNonWhite = true
        }
        i += Character.charCount(c)
    }
}

/**
 * Tests if a code point is "whitespace" as defined in the HTML spec.
 */
private fun isWhitespace(c: Int): Boolean {
    return c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\u000c'.code || c == '\r'.code
}

private fun isInvisibleChar(c: Int): Boolean {
    return c == 8203 || c == 173 // zero width sp, soft hyphen
    // previously also included zw non join, zw join - but removing those breaks semantic meaning of text
}
