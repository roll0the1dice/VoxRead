package com.roll0the1dice.voxread.desktop.util

import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class Chapter(val title: String, val href: String)

class EpubContentReader(val epubFile: File) {

    private val dbf = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        try {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Throwable) {}
    }

    val opfDir: String
    val chapters: List<Chapter>

    init {
        val (dir, chs) = ZipFile(epubFile).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?: throw IllegalArgumentException("Missing META-INF/container.xml")

            val opfPath = zip.getInputStream(container).use { isStream ->
                val doc = dbf.newDocumentBuilder().parse(isStream)
                val roots = doc.getElementsByTagName("rootfile")
                if (roots.length > 0) (roots.item(0) as Element).getAttribute("full-path")
                else throw IllegalArgumentException("No rootfile in container.xml")
            }

            val computedOpfDir = parentDir(opfPath)

            val opfEntry = zip.getEntry(opfPath)
                ?: throw IllegalArgumentException("Missing $opfPath")

            val chList = zip.getInputStream(opfEntry).use { isStream ->
                val doc = dbf.newDocumentBuilder().parse(isStream)

                val items = doc.getElementsByTagName("item")
                val idToHref = mutableMapOf<String, String>()
                var ncxHref: String? = null
                var navHref: String? = null

                for (i in 0 until items.length) {
                    val el = items.item(i) as Element
                    val id = el.getAttribute("id")
                    val href = el.getAttribute("href")
                    val mediaType = el.getAttribute("media-type")
                    val properties = el.getAttribute("properties")
                    idToHref[id] = href
                    if (mediaType == "application/x-dtbncx+xml" || href.endsWith(".ncx", ignoreCase = true)) {
                        ncxHref = href
                    }
                    if (properties.split(" ").contains("nav")) {
                        navHref = href
                    }
                }

                // Parse TOC titles mapping (normalized href without anchor -> title)
                val tocTitleMap = mutableMapOf<String, String>()
                if (ncxHref != null) {
                    parseNcxTitles(zip, resolveZipPath(computedOpfDir, ncxHref), tocTitleMap)
                }
                if (navHref != null) {
                    parseNavTitles(zip, resolveZipPath(computedOpfDir, navHref), tocTitleMap)
                }

                val itemrefs = doc.getElementsByTagName("itemref")
                val spineHrefs = mutableListOf<String>()
                for (j in 0 until itemrefs.length) {
                    val el = itemrefs.item(j) as Element
                    val idref = el.getAttribute("idref")
                    idToHref[idref]?.let { spineHrefs.add(it) }
                }

                spineHrefs.mapIndexed { idx, href ->
                    val cleanHref = href.substringBefore('#')
                    val resolvedTitle = tocTitleMap[cleanHref]
                        ?: tocTitleMap[decodeUrl(cleanHref)]
                        ?: extractHtmlTitle(zip, resolveZipPath(computedOpfDir, cleanHref))
                        ?: "第 ${idx + 1} 节"
                    Chapter(resolvedTitle, cleanHref)
                }
            }

            computedOpfDir to chList
        }
        opfDir = dir
        chapters = chs
    }

    private fun parseNcxTitles(zip: ZipFile, ncxFullPath: String, outMap: MutableMap<String, String>) {
        try {
            val entry = zip.getEntry(ncxFullPath) ?: return
            val ncxDir = parentDir(ncxFullPath)
            zip.getInputStream(entry).use { stream ->
                val doc = Jsoup.parse(stream, "UTF-8", "")
                for (navPoint in doc.select("navPoint")) {
                    val label = navPoint.selectFirst("navLabel > text")?.text()?.trim()
                    val src = navPoint.selectFirst("content")?.attr("src")?.trim()
                    if (!label.isNullOrEmpty() && !src.isNullOrEmpty()) {
                        val cleanSrc = src.substringBefore('#')
                        val fullZipPath = resolveZipPath(ncxDir, cleanSrc)
                        val relToOpf = fullZipPath.removePrefix(opfDir).removePrefix("/")
                        outMap.putIfAbsent(cleanSrc, label)
                        outMap.putIfAbsent(relToOpf, label)
                        outMap.putIfAbsent(decodeUrl(relToOpf), label)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun parseNavTitles(zip: ZipFile, navFullPath: String, outMap: MutableMap<String, String>) {
        try {
            val entry = zip.getEntry(navFullPath) ?: return
            val navDir = parentDir(navFullPath)
            zip.getInputStream(entry).use { stream ->
                val doc = Jsoup.parse(stream, "UTF-8", "")
                for (a in doc.select("nav a[href]")) {
                    val label = a.text().trim()
                    val href = a.attr("href").trim()
                    if (label.isNotEmpty() && href.isNotEmpty()) {
                        val cleanSrc = href.substringBefore('#')
                        val fullZipPath = resolveZipPath(navDir, cleanSrc)
                        val relToOpf = fullZipPath.removePrefix(opfDir).removePrefix("/")
                        outMap.putIfAbsent(cleanSrc, label)
                        outMap.putIfAbsent(relToOpf, label)
                        outMap.putIfAbsent(decodeUrl(relToOpf), label)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun extractHtmlTitle(zip: ZipFile, fullPath: String): String? {
        return try {
            val entry = zip.getEntry(fullPath) ?: zip.getEntry(decodeUrl(fullPath)) ?: return null
            zip.getInputStream(entry).use { stream ->
                val doc = Jsoup.parse(stream, "UTF-8", "")
                val h1 = doc.selectFirst("h1, h2, h3")?.text()?.trim()
                if (!h1.isNullOrEmpty()) return h1
                val title = doc.title().trim()
                if (title.isNotEmpty()) return title
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun readChapterContent(chapterIndex: Int): String {
        if (chapterIndex < 0 || chapterIndex >= chapters.size) return "<html><body><p>无内容</p></body></html>"
        val chapter = chapters[chapterIndex]
        val fullPath = resolveZipPath(opfDir, chapter.href)

        return ZipFile(epubFile).use { zip ->
            val entry = zip.getEntry(fullPath) ?: zip.getEntry(decodeUrl(fullPath))
            if (entry != null) {
                zip.getInputStream(entry).use { isStream ->
                    String(isStream.readAllBytes(), StandardCharsets.UTF_8)
                }
            } else {
                "<html><body><h3>无法加载章节: ${chapter.title}</h3></body></html>"
            }
        }
    }

    fun readResourceBytes(chapterIndex: Int, relativeSrc: String): ByteArray? {
        if (relativeSrc.isBlank() || relativeSrc.startsWith("http://") || relativeSrc.startsWith("https://")) {
            return null
        }
        val chapterHref = chapters.getOrNull(chapterIndex)?.href ?: ""
        val chapterFullPath = resolveZipPath(opfDir, chapterHref)
        val chapterDir = parentDir(chapterFullPath)
        val cleanSrc = relativeSrc.substringBefore('#').substringBefore('?')
        val targetPath = resolveZipPath(chapterDir, cleanSrc)
        val fallbackPath = resolveZipPath(opfDir, cleanSrc)

        return try {
            ZipFile(epubFile).use { zip ->
                val entry = zip.getEntry(targetPath)
                    ?: zip.getEntry(decodeUrl(targetPath))
                    ?: zip.getEntry(fallbackPath)
                    ?: zip.getEntry(decodeUrl(fallbackPath))
                    ?: zip.entries().asSequence().firstOrNull {
                        it.name.endsWith("/" + cleanSrc.substringAfterLast('/')) ||
                                it.name == cleanSrc.substringAfterLast('/')
                    }
                entry?.let { zip.getInputStream(it).use { s -> s.readAllBytes() } }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx >= 0) path.substring(0, idx + 1) else ""
    }

    private fun resolveZipPath(baseDir: String, relativePath: String): String {
        val rel = relativePath.removePrefix("/")
        val combined = if (baseDir.isEmpty()) rel else "$baseDir$rel"
        val parts = combined.split('/')
        val stack = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun decodeUrl(s: String): String {
        return try {
            URLDecoder.decode(s, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            s
        }
    }
}
