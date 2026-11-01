package com.roll0the1dice.voxread.desktop.util

import com.roll0the1dice.voxread.desktop.data.db.Database
import com.roll0the1dice.voxread.desktop.data.model.Book
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

data class EpubMetadata(
    val title: String,
    val author: String?,
    val identifier: String,
    val coverBytes: ByteArray? = null
)

object EpubParser {
    private val dbf: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        try {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Throwable) {}
    }

    fun parse(file: File): EpubMetadata {
        return ZipFile(file).use { zip ->
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: throw IllegalArgumentException("Invalid EPUB: META-INF/container.xml not found")

            val opfPath = zip.getInputStream(containerEntry).use { input ->
                val doc = dbf.newDocumentBuilder().parse(input)
                val rootfiles = doc.getElementsByTagName("rootfile")
                if (rootfiles.length > 0) {
                    (rootfiles.item(0) as Element).getAttribute("full-path")
                } else {
                    throw IllegalArgumentException("No rootfile element found in container.xml")
                }
            }

            val opfEntry = zip.getEntry(opfPath)
                ?: throw IllegalArgumentException("OPF file not found at $opfPath")

            val (meta, coverHref) = zip.getInputStream(opfEntry).use { input ->
                val doc = dbf.newDocumentBuilder().parse(input)
                val title = getTagText(doc, "title") ?: file.nameWithoutExtension
                val author = getTagText(doc, "creator")
                val identifier = getTagText(doc, "identifier") ?: file.name

                val coverId = findCoverId(doc)
                val coverHref = coverId?.let { findItemHref(doc, it) }
                    ?: findItemByProperties(doc, "cover-image")
                    ?: findItemByMediaType(doc, "image/")

                EpubMetadata(title, author, identifier) to coverHref
            }

            val coverBytes = coverHref?.let { relPath ->
                val fullCoverPath = resolveRelativePath(opfPath, relPath)
                zip.getEntry(fullCoverPath)?.let { entry ->
                    zip.getInputStream(entry).use { it.readAllBytes() }
                }
            }

            meta.copy(coverBytes = coverBytes)
        }
    }

    fun importEpubFile(file: File): Book {
        val meta = try {
            parse(file)
        } catch (_: Throwable) {
            EpubMetadata(
                title = file.nameWithoutExtension,
                author = null,
                identifier = file.name
            )
        }

        val coverPath = meta.coverBytes?.let { bytes ->
            val hash = abs(file.absolutePath.hashCode())
            val coverFile = File(Database.coversDir, "cover_$hash.png")
            FileOutputStream(coverFile).use { it.write(bytes) }
            coverFile.absolutePath
        }

        return Book(
            href = file.absolutePath,
            title = meta.title,
            author = meta.author,
            identifier = meta.identifier,
            mediaType = "application/epub+zip",
            cover = coverPath
        )
    }

    private fun getTagText(doc: Document, localName: String): String? {
        val list = doc.getElementsByTagNameNS("*", localName)
        if (list.length > 0) {
            val text = list.item(0).textContent
            if (!text.isNullOrBlank()) return text.trim()
        }
        return null
    }

    private fun findCoverId(doc: Document): String? {
        val metaNodes = doc.getElementsByTagName("meta")
        for (i in 0 until metaNodes.length) {
            val el = metaNodes.item(i) as Element
            if (el.getAttribute("name") == "cover") {
                return el.getAttribute("content")
            }
        }
        return null
    }

    private fun findItemHref(doc: Document, id: String): String? {
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val el = items.item(i) as Element
            if (el.getAttribute("id") == id) {
                return el.getAttribute("href")
            }
        }
        return null
    }

    private fun findItemByProperties(doc: Document, prop: String): String? {
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val el = items.item(i) as Element
            if (el.getAttribute("properties").contains(prop)) {
                return el.getAttribute("href")
            }
        }
        return null
    }

    private fun findItemByMediaType(doc: Document, prefix: String): String? {
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val el = items.item(i) as Element
            val mediaType = el.getAttribute("media-type")
            val id = el.getAttribute("id").lowercase()
            if (mediaType.startsWith(prefix) && id.contains("cover")) {
                return el.getAttribute("href")
            }
        }
        return null
    }

    private fun resolveRelativePath(base: String, relative: String): String {
        val lastSlash = base.lastIndexOf('/')
        return if (lastSlash >= 0) {
            base.substring(0, lastSlash + 1) + relative
        } else relative
    }
}
