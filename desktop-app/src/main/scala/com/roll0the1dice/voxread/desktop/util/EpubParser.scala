package com.roll0the1dice.voxread.desktop.util

import java.io.{File, FileOutputStream, InputStream}
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Document, Element, NodeList}
import scala.util.Using
import com.roll0the1dice.voxread.desktop.data.model.Book
import com.roll0the1dice.voxread.desktop.data.db.Database

case class EpubMetadata(
  title: String,
  author: Option[String],
  identifier: String,
  coverBytes: Option[Array[Byte]] = None
)

object EpubParser:

  private val dbf = DocumentBuilderFactory.newInstance()
  dbf.setNamespaceAware(true)
  // Disable external DTDs for security
  try
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
  catch
    case _: Throwable => ()

  def parse(file: File): EpubMetadata =
    Using.resource(new ZipFile(file)) { zip =>
      // 1. Read META-INF/container.xml
      val containerEntry = Option(zip.getEntry("META-INF/container.xml"))
        .getOrElse(throw new IllegalArgumentException("Invalid EPUB: META-INF/container.xml not found"))

      val opfPath = Using.resource(zip.getInputStream(containerEntry)) { is =>
        val doc = dbf.newDocumentBuilder().parse(is)
        val rootfiles = doc.getElementsByTagName("rootfile")
        if (rootfiles.getLength > 0)
          rootfiles.item(0).asInstanceOf[Element].getAttribute("full-path")
        else
          throw new IllegalArgumentException("No rootfile element found in container.xml")
      }

      // 2. Read OPF
      val opfEntry = Option(zip.getEntry(opfPath))
        .getOrElse(throw new IllegalArgumentException(s"OPF file not found at $opfPath"))

      val (metadata, coverHref) = Using.resource(zip.getInputStream(opfEntry)) { is =>
        val doc = dbf.newDocumentBuilder().parse(is)
        val title = getTagText(doc, "title").getOrElse(file.getName.replaceAll("\\.[^.]+$", ""))
        val author = getTagText(doc, "creator")
        val identifier = getTagText(doc, "identifier").getOrElse(file.getName)

        // Find cover href
        val coverId = findCoverId(doc)
        val coverHref = coverId.flatMap(id => findItemHref(doc, id))
          .orElse(findItemByProperties(doc, "cover-image"))
          .orElse(findItemByMediaType(doc, "image/"))

        (EpubMetadata(title, author, identifier), coverHref)
      }

      // 3. Extract cover image bytes if present
      val coverBytes = coverHref.flatMap { relPath =>
        val fullCoverPath = resolveRelativePath(opfPath, relPath)
        Option(zip.getEntry(fullCoverPath)).map { entry =>
          Using.resource(zip.getInputStream(entry))(_.readAllBytes())
        }
      }

      metadata.copy(coverBytes = coverBytes)
    }

  def importEpubFile(file: File): Book =
    val meta = try
      parse(file)
    catch
      case _: Throwable =>
        EpubMetadata(
          title = file.getName.replaceAll("\\.[^.]+$", ""),
          author = None,
          identifier = file.getName
        )

    val coverPath = meta.coverBytes.map { bytes =>
      val hash = Math.abs(file.getAbsolutePath.hashCode)
      val coverFile = new File(Database.coversDir, s"cover_$hash.png")
      Using.resource(new FileOutputStream(coverFile))(_.write(bytes))
      coverFile.getAbsolutePath
    }

    Book(
      href = file.getAbsolutePath,
      title = meta.title,
      author = meta.author,
      identifier = meta.identifier,
      mediaType = "application/epub+zip",
      cover = coverPath
    )

  private def getTagText(doc: Document, localName: String): Option[String] =
    val list = doc.getElementsByTagNameNS("*", localName)
    if (list.getLength > 0)
      val text = list.item(0).getTextContent
      if (text != null && text.trim.nonEmpty) Some(text.trim) else None
    else None

  private def findCoverId(doc: Document): Option[String] =
    val metaNodes = doc.getElementsByTagName("meta")
    var i = 0
    while (i < metaNodes.getLength)
      val el = metaNodes.item(i).asInstanceOf[Element]
      if (el.getAttribute("name") == "cover")
        return Some(el.getAttribute("content"))
      i += 1
    None

  private def findItemHref(doc: Document, id: String): Option[String] =
    val items = doc.getElementsByTagName("item")
    var i = 0
    while (i < items.getLength)
      val el = items.item(i).asInstanceOf[Element]
      if (el.getAttribute("id") == id)
        return Some(el.getAttribute("href"))
      i += 1
    None

  private def findItemByProperties(doc: Document, prop: String): Option[String] =
    val items = doc.getElementsByTagName("item")
    var i = 0
    while (i < items.getLength)
      val el = items.item(i).asInstanceOf[Element]
      if (el.getAttribute("properties").contains(prop))
        return Some(el.getAttribute("href"))
      i += 1
    None

  private def findItemByMediaType(doc: Document, prefix: String): Option[String] =
    val items = doc.getElementsByTagName("item")
    var i = 0
    while (i < items.getLength)
      val el = items.item(i).asInstanceOf[Element]
      val mediaType = el.getAttribute("media-type")
      val id = el.getAttribute("id").toLowerCase
      if (mediaType.startsWith(prefix) && id.contains("cover"))
        return Some(el.getAttribute("href"))
      i += 1
    None

  private def resolveRelativePath(base: String, relative: String): String =
    val lastSlash = base.lastIndexOf('/')
    if (lastSlash >= 0)
      val prefix = base.substring(0, lastSlash + 1)
      prefix + relative
    else relative
