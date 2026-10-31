package com.roll0the1dice.voxread.desktop.util

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import scala.util.Using

case class Chapter(title: String, href: String)

class EpubContentReader(val epubFile: File):

  private val dbf = DocumentBuilderFactory.newInstance()
  dbf.setNamespaceAware(true)
  try
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
  catch
    case _: Throwable => ()

  lazy val (opfDir: String, chapters: List[Chapter]) =
    Using.resource(new ZipFile(epubFile)) { zip =>
      // 1. container.xml
      val container = Option(zip.getEntry("META-INF/container.xml"))
        .getOrElse(throw new IllegalArgumentException("Missing META-INF/container.xml"))

      val opfPath = Using.resource(zip.getInputStream(container)) { is =>
        val doc = dbf.newDocumentBuilder().parse(is)
        val roots = doc.getElementsByTagName("rootfile")
        if (roots.getLength > 0) roots.item(0).asInstanceOf[Element].getAttribute("full-path")
        else throw new IllegalArgumentException("No rootfile in container.xml")
      }

      val opfDir =
        val idx = opfPath.lastIndexOf('/')
        if (idx >= 0) opfPath.substring(0, idx + 1) else ""

      // 2. OPF
      val opfEntry = Option(zip.getEntry(opfPath))
        .getOrElse(throw new IllegalArgumentException(s"Missing $opfPath"))

      val chs = Using.resource(zip.getInputStream(opfEntry)) { is =>
        val doc = dbf.newDocumentBuilder().parse(is)

        // Map id -> href
        val items = doc.getElementsByTagName("item")
        val idToHref = collection.mutable.Map[String, String]()
        var i = 0
        while (i < items.getLength)
          val el = items.item(i).asInstanceOf[Element]
          idToHref(el.getAttribute("id")) = el.getAttribute("href")
          i += 1

        // Spine
        val itemrefs = doc.getElementsByTagName("itemref")
        val spineHrefs = collection.mutable.ListBuffer[String]()
        var j = 0
        while (j < itemrefs.getLength)
          val el = itemrefs.item(j).asInstanceOf[Element]
          val idref = el.getAttribute("idref")
          idToHref.get(idref).foreach(spineHrefs += _)
          j += 1

        spineHrefs.zipWithIndex.map { case (href, idx) =>
          Chapter(s"第 ${idx + 1} 节", href)
        }.toList
      }

      (opfDir, chs)
    }

  def readChapterContent(chapterIndex: Int): String =
    if (chapterIndex < 0 || chapterIndex >= chapters.size) return "<html><body><p>无内容</p></body></html>"
    val chapter = chapters(chapterIndex)
    val fullPath = opfDir + chapter.href

    Using.resource(new ZipFile(epubFile)) { zip =>
      Option(zip.getEntry(fullPath)) match
        case Some(entry) =>
          Using.resource(zip.getInputStream(entry)) { is =>
            new String(is.readAllBytes(), StandardCharsets.UTF_8)
          }
        case None =>
          s"<html><body><h3>无法加载章节: ${chapter.title}</h3></body></html>"
    }
