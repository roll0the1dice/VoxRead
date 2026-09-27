@file:OptIn(ExperimentalReadiumApi::class, ExperimentalCoroutinesApi::class)

package org.readium.r2.shared.publication.services.content.iterators

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.Content.Attribute
import org.readium.r2.shared.publication.services.content.Content.AttributeKey.Companion.ACCESSIBILITY_LABEL
import org.readium.r2.shared.publication.services.content.Content.AttributeKey.Companion.LANGUAGE
import org.readium.r2.shared.publication.services.content.Content.TextElement
import org.readium.r2.shared.publication.services.content.Content.TextElement.Segment
import org.readium.r2.shared.publication.services.content.SpeechAnchors
import org.readium.r2.shared.publication.services.content.SpeechMap
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.StringResource
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalReadiumApi::class)
@RunWith(RobolectricTestRunner::class)
class HtmlResourceContentIteratorTest {

    private val locator = Locator(href = Url("/dir/res.xhtml")!!, mediaType = MediaType.XHTML)

    private val html = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en">
            <head>
                <title>Section IV: FAIRY STORIES—MODERN FANTASTIC TALES</title>
                <link href="css/epub.css" type="text/css" rel="stylesheet" />
            </head>
            <body>
                 <section id="pgepubid00498">
                     <div class="center"><span epub:type="pagebreak" title="171" id="Page_171">171</span></div>
                     <h3>INTRODUCTORY</h3>
                     
                     <p>The difficulties of classification are very apparent here, and once more it must be noted that illustrative and practical purposes rather than logical ones are served by the arrangement adopted. The modern fanciful story is here placed next to the real folk story instead of after all the groups of folk products. The Hebrew stories at the beginning belong quite as well, perhaps even better, in Section V, while the stories at the end of Section VI shade off into the more modern types of short tales.</p>
                     <p><span>The child's natural literature.</span> The world has lost certain secrets as the price of an advancing civilization.</p>
                     <p>Without discussing the limits of the culture-epoch theory of human development as a complete guide in education, it is clear that the young child passes through a period when his mind looks out upon the world in a manner analogous to that of the folk as expressed in their literature.</p>
                </section>
            </body>
        </html>
        """

    private val elements: List<Content.Element> = listOf(
        TextElement(
            locator = locator(
                progression = 0.0,
                selector = "#pgepubid00498 > div.center",
                before = null,
                highlight = "171"
            ),
            role = TextElement.Role.Body,
            segments = listOf(
                Segment(
                    locator = locator(
                        progression = 0.0,
                        selector = "#pgepubid00498 > div.center",
                        before = null,
                        highlight = "171"
                    ),
                    text = "171",
                    attributes = listOf(Attribute(LANGUAGE, Language("en")))
                )
            )
        ),
        TextElement(
            locator = locator(
                progression = 0.2,
                selector = "#pgepubid00498 > h3",
                before = "171",
                highlight = "INTRODUCTORY"
            ),
            role = TextElement.Role.Body,
            segments = listOf(
                Segment(
                    locator = locator(
                        progression = 0.2,
                        selector = "#pgepubid00498 > h3",
                        before = "171",
                        highlight = "INTRODUCTORY"
                    ),
                    text = "INTRODUCTORY",
                    attributes = listOf(Attribute(LANGUAGE, Language("en")))
                )
            )
        ),
        TextElement(
            locator = locator(
                progression = 0.4,
                selector = "#pgepubid00498 > p:nth-child(3)",
                before = "171INTRODUCTORY",
                highlight = "The difficulties of classification are very apparent here, and once more it must be noted that illustrative and practical purposes rather than logical ones are served by the arrangement adopted. The modern fanciful story is here placed next to the real folk story instead of after all the groups of folk products. The Hebrew stories at the beginning belong quite as well, perhaps even better, in Section V, while the stories at the end of Section VI shade off into the more modern types of short tales."
            ),
            role = TextElement.Role.Body,
            segments = listOf(
                Segment(
                    locator = locator(
                        progression = 0.4,
                        selector = "#pgepubid00498 > p:nth-child(3)",
                        before = "171INTRODUCTORY",
                        highlight = "The difficulties of classification are very apparent here, and once more it must be noted that illustrative and practical purposes rather than logical ones are served by the arrangement adopted. The modern fanciful story is here placed next to the real folk story instead of after all the groups of folk products. The Hebrew stories at the beginning belong quite as well, perhaps even better, in Section V, while the stories at the end of Section VI shade off into the more modern types of short tales."
                    ),
                    text = "The difficulties of classification are very apparent here, and once more it must be noted that illustrative and practical purposes rather than logical ones are served by the arrangement adopted. The modern fanciful story is here placed next to the real folk story instead of after all the groups of folk products. The Hebrew stories at the beginning belong quite as well, perhaps even better, in Section V, while the stories at the end of Section VI shade off into the more modern types of short tales.",
                    attributes = listOf(Attribute(LANGUAGE, Language("en")))
                )
            )
        ),
        TextElement(
            locator = locator(
                progression = 0.6,
                selector = "#pgepubid00498 > p:nth-child(4)",
                before = "ade off into the more modern types of short tales.",
                highlight = "The child's natural literature. The world has lost certain secrets as the price of an advancing civilization."
            ),
            role = TextElement.Role.Body,
            segments = listOf(
                Segment(
                    locator = locator(
                        progression = 0.6,
                        selector = "#pgepubid00498 > p:nth-child(4)",
                        before = "ade off into the more modern types of short tales.",
                        highlight = "The child's natural literature. The world has lost certain secrets as the price of an advancing civilization."
                    ),
                    text = "The child's natural literature. The world has lost certain secrets as the price of an advancing civilization.",
                    attributes = listOf(Attribute(LANGUAGE, Language("en")))
                )
            )
        ),
        TextElement(
            locator = locator(
                progression = 0.8,
                selector = "#pgepubid00498 > p:nth-child(5)",
                before = "secrets as the price of an advancing civilization.",
                highlight = "Without discussing the limits of the culture-epoch theory of human development as a complete guide in education, it is clear that the young child passes through a period when his mind looks out upon the world in a manner analogous to that of the folk as expressed in their literature."
            ),
            role = TextElement.Role.Body,
            segments = listOf(
                Segment(
                    locator = locator(
                        progression = 0.8,
                        selector = "#pgepubid00498 > p:nth-child(5)",
                        before = "secrets as the price of an advancing civilization.",
                        highlight = "Without discussing the limits of the culture-epoch theory of human development as a complete guide in education, it is clear that the young child passes through a period when his mind looks out upon the world in a manner analogous to that of the folk as expressed in their literature."
                    ),
                    text = "Without discussing the limits of the culture-epoch theory of human development as a complete guide in education, it is clear that the young child passes through a period when his mind looks out upon the world in a manner analogous to that of the folk as expressed in their literature.",
                    attributes = listOf(Attribute(LANGUAGE, Language("en")))
                )
            )
        )
    )

    private fun locator(
        progression: Double? = null,
        selector: String? = null,
        before: String? = null,
        highlight: String? = null,
        after: String? = null,
    ): Locator =
        locator.copy(
            locations = Locator.Locations(
                progression = progression,
                otherLocations = buildMap {
                    selector?.let { put("cssSelector", it) }
                }
            ),
            text = Locator.Text(before = before, highlight = highlight, after = after)
        )

    private fun iterator(
        html: String,
        startLocator: Locator = locator,
        totalProgressionRange: ClosedRange<Double>? = null,
    ): HtmlResourceContentIterator =
        HtmlResourceContentIterator(
            StringResource(html),
            totalProgressionRange = totalProgressionRange,
            startLocator
        )

    private suspend fun HtmlResourceContentIterator.elements(): List<Content.Element> =
        buildList {
            while (hasNext()) {
                add(next())
            }
        }

    @Test
    fun `cannot call previous() without first hasPrevious()`() = runTest {
        val iter = iterator(html)
        iter.hasNext()
        iter.next()
        iter.hasNext()
        iter.next()

        assertThrows(IllegalStateException::class.java) { iter.previous() }
        iter.hasPrevious()
        iter.previous()
    }

    @Test
    fun `cannot call next() without first hasNext()`() = runTest {
        val iter = iterator(html)
        assertThrows(IllegalStateException::class.java) { iter.next() }
        iter.hasNext()
        iter.next()
    }

    @Test
    fun `iterate from start to finish`() = runTest {
        assertEquals(elements, iterator(html).elements())
    }

    @Test
    fun `previous() is null from the beginning`() = runTest {
        val iter = iterator(html)
        assertFalse(iter.hasPrevious())
    }

    @Test
    fun `next() returns the first element from the beginning`() = runTest {
        val iter = iterator(html)
        assertTrue(iter.hasNext())
        assertEquals(elements[0], iter.next())
    }

    @Test
    fun `next() then previous() returns null`() = runTest {
        val iter = iterator(html)
        assertTrue(iter.hasNext())
        assertEquals(elements[0], iter.next())
        assertFalse(iter.hasPrevious())
    }

    @Test
    fun `next() twice then previous() returns the first element`() = runTest {
        val iter = iterator(html)
        assertTrue(iter.hasNext())
        assertEquals(elements[0], iter.next())
        assertTrue(iter.hasNext())
        assertEquals(elements[1], iter.next())
        assertTrue(iter.hasPrevious())
        assertEquals(elements[0], iter.previous())
    }

    @Test
    fun `calling hasPrevious() several times doesn't move the index`() = runTest {
        val iter = iterator(html)
        iter.hasNext()
        iter.next()
        iter.hasNext()
        iter.next()
        assertTrue(iter.hasPrevious())
        assertTrue(iter.hasPrevious())
        assertTrue(iter.hasPrevious())
        assertEquals(elements[0], iter.previous())
    }

    @Test
    fun `calling hasNext() several times doesn't move the index`() = runTest {
        val iter = iterator(html)
        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())
        assertTrue(iter.hasNext())
        assertEquals(elements[0], iter.next())
    }

    @Test
    fun `starting from a CSS selector`() = runTest {
        val iter = iterator(html, locator(selector = "html > body > section > p:nth-child(3)"))
        assertEquals(elements.subList(2, elements.size), iter.elements())
    }

    @Test
    fun `calling previous() when starting from a CSS selector`() = runTest {
        val iter = iterator(html, locator(selector = "html > body > section > p:nth-child(3)"))
        assertTrue(iter.hasPrevious())
        assertEquals(elements[1], iter.previous())
    }

    @Test
    fun `starting from a CSS selector to a block element containing an inline element`() = runTest {
        val nbspHtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="fr">
            <body>
                <p>Tout au loin sur la chaussée, aussi loin qu’on pouvait voir</p>
                <p>Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient <span>[...]</span> On buvait de la bière sucrée.</p>
            </body>
            </html>
            """

        val iter = iterator(nbspHtml, locator(selector = ":root > :nth-child(2) > :nth-child(2)"))
        assertTrue(iter.hasNext())
        assertEquals(
            TextElement(
                locator = locator(
                    progression = 0.5,
                    selector = "html > body > p:nth-child(2)",
                    before = "oin sur la chaussée, aussi loin qu’on pouvait voir",
                    highlight = "Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient [...] On buvait de la bière sucrée."
                ),
                role = TextElement.Role.Body,
                segments = listOf(
                    Segment(
                        locator = locator(
                            progression = 0.5,
                            selector = "html > body > p:nth-child(2)",
                            before = "oin sur la chaussée, aussi loin qu’on pouvait voir",
                            highlight = "Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient [...] On buvait de la bière sucrée."
                        ),
                        text = "Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient [...] On buvait de la bière sucrée.",
                        attributes = listOf(Attribute(LANGUAGE, Language("fr")))
                    )
                )
            ),
            iter.next()
        )
    }

    @Test
    fun `starting from a CSS selector using the root selector`() = runTest {
        val nbspHtml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="fr">
            <head></head>
            <body>
                <p>Tout au loin sur la chaussée, aussi loin qu’on pouvait voir</p>
                <p>Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient <span>[...]</span> On buvait de la bière sucrée.</p>
            </body>
            </html>
            """

        val iter = iterator(nbspHtml, locator(selector = ":root > :nth-child(2) > :nth-child(2)"))
        assertTrue(iter.hasNext())
        assertEquals(
            TextElement(
                locator = locator(
                    progression = 0.5,
                    selector = "html > body > p:nth-child(2)",
                    before = "oin sur la chaussée, aussi loin qu’on pouvait voir",
                    highlight = "Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient [...] On buvait de la bière sucrée."
                ),
                role = TextElement.Role.Body,
                segments = listOf(
                    Segment(
                        locator = locator(
                            progression = 0.5,
                            selector = "html > body > p:nth-child(2)",
                            before = "oin sur la chaussée, aussi loin qu’on pouvait voir",
                            highlight = "Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient [...] On buvait de la bière sucrée."
                        ),
                        text = "Lui, notre colonel, savait peut-être pourquoi ces deux gens-là tiraient [...] On buvait de la bière sucrée.",
                        attributes = listOf(Attribute(LANGUAGE, Language("fr")))
                    )
                )
            ),
            iter.next()
        )
    }

    @Test
    fun `iterating over image elements`() = runTest {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <img src="image.png"/>
                <img src="../cover.jpg" alt="Accessibility description" />
            </body>
            </html>
            """

        assertEquals(
            listOf(
                Content.ImageElement(
                    locator = locator(
                        progression = 0.0,
                        selector = "html > body > img:nth-child(1)"
                    ),
                    embeddedLink = Link(href = Href("/dir/image.png")!!),
                    caption = null,
                    attributes = emptyList()
                ),
                Content.ImageElement(
                    locator = locator(
                        progression = 0.5,
                        selector = "html > body > img:nth-child(2)"
                    ),
                    embeddedLink = Link(href = Href("/cover.jpg")!!),
                    caption = null,
                    attributes = listOf(Attribute(ACCESSIBILITY_LABEL, "Accessibility description"))
                )
            ),
            iterator(html).elements()
        )
    }

    @Test
    fun `iterating over audio elements`() = runTest {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <audio src="audio.mp3"></audio>
                <audio>
                    <source src="audio.mp3" type="audio/mpeg" />
                    <source src="audio.ogg" type="audio/ogg" />
                </audio>
            </body>
            </html>
            """

        assertEquals(
            listOf(
                Content.AudioElement(
                    locator = locator(
                        progression = 0.0,
                        selector = "html > body > audio:nth-child(1)"
                    ),
                    embeddedLink = Link(href = Href("/dir/audio.mp3")!!),
                    attributes = emptyList()
                ),
                Content.AudioElement(
                    locator = locator(
                        progression = 0.5,
                        selector = "html > body > audio:nth-child(2)"
                    ),
                    embeddedLink = Link(
                        href = Href("/dir/audio.mp3")!!,
                        mediaType = MediaType.MP3,
                        alternates = listOf(
                            Link(href = Href("/dir/audio.ogg")!!, mediaType = MediaType.OGG)
                        )
                    ),
                    attributes = emptyList()
                )
            ),
            iterator(html).elements()
        )
    }

    @Test
    fun `iterating over video elements`() = runTest {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <video src="video.mp4"></video>
                <video>
                    <source src="video.mp4" type="video/mp4" />
                    <source src="video.m4v" type="video/x-m4v" />
                </video>
            </body>
            </html>
            """

        assertEquals(
            listOf(
                Content.VideoElement(
                    locator = locator(
                        progression = 0.0,
                        selector = "html > body > video:nth-child(1)"
                    ),
                    embeddedLink = Link(href = Href("/dir/video.mp4")!!),
                    attributes = emptyList()
                ),
                Content.VideoElement(
                    locator = locator(
                        progression = 0.5,
                        selector = "html > body > video:nth-child(2)"
                    ),
                    embeddedLink = Link(
                        href = Href("/dir/video.mp4")!!,
                        mediaType = MediaType("video/mp4")!!,
                        alternates = listOf(
                            Link(
                                href = Href("/dir/video.m4v")!!,
                                mediaType = MediaType("video/x-m4v")!!
                            )
                        )
                    ),
                    attributes = emptyList()
                )
            ),
            iterator(html).elements()
        )
    }

    @Test
    fun `iterating over an element containing both a text node and child elements`() = runTest {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <ol class="decimal" id="c06-list-0001">
                    <li id="c06-li-0001">Let&#39;s start at the top&#8212;the <i>source of ideas</i>.
                        <aside><div class="top hr"><hr/></div>
                        <section class="feature1">
                            <p id="c06-para-0019"><i>While almost everyone today claims to be Agile, what I&#39;ve just described is very much a <i>waterfall</i> process.</i></p>
                        </section>
                        Trailing text
                    </li>
                </ol>
            </body>
            </html>
            """

        assertEquals(
            listOf(
                TextElement(
                    locator = locator(
                        progression = 0.0,
                        selector = "#c06-li-0001",
                        highlight = "Let's start at the top—the source of ideas."
                    ),
                    role = TextElement.Role.Body,
                    segments = listOf(
                        Segment(
                            locator = locator(
                                progression = 0.0,
                                selector = "#c06-li-0001",
                                highlight = "Let's start at the top—the source of ideas."
                            ),
                            text = "Let's start at the top—the source of ideas.",
                            attributes = emptyList()
                        )
                    ),
                    attributes = emptyList()
                ),
                TextElement(
                    locator = locator(
                        progression = 1 / 3.0,
                        selector = "#c06-para-0019",
                        before = " top—the source of ideas.\n                        ",
                        highlight = "While almost everyone today claims to be Agile, what I've just described is very much a waterfall process."
                    ),
                    role = TextElement.Role.Body,
                    segments = listOf(
                        Segment(
                            locator = locator(
                                progression = 1 / 3.0,
                                selector = "#c06-para-0019",
                                before = " top—the source of ideas.\n                        ",
                                highlight = "While almost everyone today claims to be Agile, what I've just described is very much a waterfall process."
                            ),
                            text = "While almost everyone today claims to be Agile, what I've just described is very much a waterfall process.",
                            attributes = emptyList()
                        )
                    ),
                    attributes = emptyList()
                ),
                TextElement(
                    locator = locator(
                        progression = 2 / 3.0,
                        selector = "#c06-li-0001 > aside",
                        before = "e just described is very much a waterfall process.\n                        \n                        ",
                        highlight = "Trailing text"
                    ),
                    role = TextElement.Role.Body,
                    segments = listOf(
                        Segment(
                            locator = locator(
                                progression = 2 / 3.0,
                                selector = "#c06-li-0001 > aside",
                                before = "e just described is very much a waterfall process.\n                        ",
                                highlight = "Trailing text"
                            ),
                            text = "Trailing text",
                            attributes = emptyList()
                        )
                    ),
                    attributes = emptyList()
                )
            ),
            iterator(html).elements()
        )
    }

    @Test
    fun `iterating over text nodes located around a nested block element`() = runTest {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <div id="a">begin a <div id="b">in b</div> end a</div>
                <div id="c">in c</div>
            </body>
            </html>
            """

        assertEquals(
            listOf(
                TextElement(
                    locator = locator(
                        progression = 0.0,
                        selector = "#a",
                        highlight = "begin a"
                    ),
                    role = TextElement.Role.Body,
                    segments = listOf(
                        Segment(
                            locator = locator(
                                progression = 0.0,
                                selector = "#a",
                                highlight = "begin a"
                            ),
                            text = "begin a",
                            attributes = emptyList()
                        )
                    ),
                    attributes = emptyList()
                ),
                TextElement(
                    locator = locator(
                        progression = 0.25,
                        selector = "#b",
                        before = "begin a ",
                        highlight = "in b"
                    ),
                    role = TextElement.Role.Body,
                    segments = listOf(
                        Segment(
                            locator = locator(
                                progression = 0.25,
                                selector = "#b",
                                before = "begin a ",
                                highlight = "in b"
                            ),
                            text = "in b",
                            attributes = emptyList()
                        )
                    ),
                    attributes = emptyList()
                ),
                TextElement(
                    locator = locator(
                        progression = 0.5,
                        selector = "#a",
                        before = "begin a in b  ",
                        highlight = "end a"
                    ),
                    role = TextElement.Role.Body,
                    segments = listOf(
                        Segment(
                            locator = locator(
                                progression = 0.5,
                                selector = "#a",
                                before = "begin a in b ",
                                highlight = "end a"
                            ),
                            text = "end a",
                            attributes = emptyList()
                        )
                    ),
                    attributes = emptyList()
                ),
                TextElement(
                    locator = locator(
                        progression = 0.75,
                        selector = "#c",
                        before = "begin a in b end a",
                        highlight = "in c"
                    ),
                    role = TextElement.Role.Body,
                    segments = listOf(
                        Segment(
                            locator = locator(
                                progression = 0.75,
                                selector = "#c",
                                before = "begin a in b end a",
                                highlight = "in c"
                            ),
                            text = "in c",
                            attributes = emptyList()
                        )
                    ),
                    attributes = emptyList()
                )
            ),
            iterator(html).elements()
        )
    }

    @Test
    fun `formula locators point at each math element and keep inline sentences`() = runTest {
        MathSpeechLanguage.code = null
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
            <body>
                <p><math id="a" display="block"><mi>x</mi></math></p>
                <p><math id="b" display="block"><mi>x</mi></math></p>
                <p>See <math id="c"><mi>y</mi></math> here.</p>
            </body>
            </html>
            """

        val segments = iterator(html).elements()
            .filterIsInstance<TextElement>()
            .flatMap { it.segments }

        assertEquals(3, segments.size)
        assertEquals(true, segments[0].locator.locations["isMath"])
        assertEquals("#a", segments[0].locator.locations["mathSelector"])
        assertEquals(SpeechAnchors.scope(locator.href.toString(), "a"), segments[0].locator.locations["mathId"])
        assertEquals(true, segments[1].locator.locations["isMath"])
        assertEquals("#b", segments[1].locator.locations["mathSelector"])
        assertEquals(SpeechAnchors.scope(locator.href.toString(), "b"), segments[1].locator.locations["mathId"])
        assertEquals(true, segments[2].locator.locations["hasInlineMath"])
        assertNull(segments[2].locator.locations["isMath"])
        assertNull(segments[2].locator.locations["mathSelector"])
        assertTrue(segments[2].locator.text.highlight!!.contains("See"))
        assertTrue(segments[2].locator.text.highlight!!.contains("here"))
    }

    @Test
    fun `math without an id gets a stable selector`() = runTest {
        MathSpeechLanguage.code = null
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
            <body>
                <p><math display="block"><mi>x</mi></math></p>
                <p><math id="keep" display="block"><mi>y</mi></math></p>
            </body>
            </html>
            """

        val segments = iterator(html).elements()
            .filterIsInstance<TextElement>()
            .flatMap { it.segments }

        assertEquals("#vox-math-0", segments[0].locator.locations["mathSelector"])
        assertEquals(SpeechAnchors.scope(locator.href.toString(), "vox-math-0"), segments[0].locator.locations["mathId"])
        assertEquals("#keep", segments[1].locator.locations["mathSelector"])
        assertEquals(SpeechAnchors.scope(locator.href.toString(), "keep"), segments[1].locator.locations["mathId"])
    }

    @Test
    fun `formula speech cache is keyed by language`() = runTest {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <p><math display="block"><semantics><mi>x</mi><annotation>x^2</annotation></semantics></math></p>
            </body>
            </html>
            """
        try {
            MathSpeechLanguage.code = "en"
            val english = iterator(html).elements()
                .filterIsInstance<TextElement>()
                .single()
                .segments
                .single()
                .text
            MathSpeechLanguage.code = "zh"
            val chinese = iterator(html).elements()
                .filterIsInstance<TextElement>()
                .single()
                .segments
                .single()
                .text
            assertTrue(english.contains("squared"))
            assertTrue(chinese.contains("平方"))
        } finally {
            MathSpeechLanguage.code = null
        }
    }

    @Test
    fun `inline formulas map speech back to each source node`() = runTest {
        MathSpeechLanguage.code = "zh"
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="zh">
            <body>
                <p id="p1">设函数 <math id="h1"><semantics><msub><mi>H</mi><mo>*</mo></msub><annotation>H_*</annotation></semantics></math> 恰好具有一个零点。当 <math id="eta"><semantics><mrow><msub><mi>η</mi><mn>0</mn></msub><mo>∈</mo><mo>(</mo><mo>-</mo><mn>1</mn><mo>,</mo><mn>0</mn><mo>)</mo></mrow><annotation>\eta_0 \in (-1,0)</annotation></semantics></math> 时，函数 <math id="h2"><semantics><msub><mi>H</mi><mo>*</mo></msub><annotation>H_*</annotation></semantics></math> 仍然连续。</p>
                <p id="p2">重复的 <math id="h3"><semantics><msub><mi>H</mi><mo>*</mo></msub><annotation>H_*</annotation></semantics></math> 与紧随的 <math id="eta2"><semantics><annotation>\eta_0 \in (-1,0)</annotation><mi>η</mi></semantics></math><math id="h4"><semantics><annotation>H_*</annotation><mi>H</mi></semantics></math> 之后回到中文。</p>
                <p id="p3">见 <math id="mx"><semantics><mtext>其中</mtext><mi>x</mi><annotation>HIDDENLATEX</annotation></semantics></math> 结束。</p>
                <p id="p4"><math id="split" display="block"><semantics><mi>x</mi><annotation>第一句。第二句</annotation></semantics></math></p>
            </body>
            </html>
            """
        try {
            val first = paragraph(html, "p1")
            val map = checkNotNull(SpeechMap.from(first.locator))
            val mathIds = map.spans.filter { it.kind == SpeechMap.Kind.Math }.map { it.mathId }
            assertEquals(
                listOf("h1", "eta", "h2").map { SpeechAnchors.scope(locator.href.toString(), it) },
                mathIds
            )
            assertEquals(mathIds.distinct(), mathIds)
            val prose = map.spans.first { it.kind == SpeechMap.Kind.Text && it.raw.contains("恰好具有一个零点") }
            assertEquals("#p1", prose.selector)
            assertTrue(prose.node > 0)
            assertFalse(prose.raw.contains("设函数"))

            val h1 = map.spans.first { it.mathId.endsWith("#h1") }
            val onFormula = map.resolve(h1.start..h1.start)
            assertEquals(false, onFormula.sentenceLevel)
            assertEquals(h1.mathId, onFormula.spans.single().mathId)

            val exactly = SpeechMap.normalize(prose.raw, stripLeading = false).text.indexOf("恰")
            val onWord = map.resolve((prose.start + exactly)..(prose.start + exactly))
            assertEquals(SpeechMap.Kind.Text, onWord.spans.single().kind)
            assertEquals("恰", onWord.spans.single().raw)
            assertTrue(onWord.spans.single().suffix.startsWith("好"))
            assertTrue(onWord.spans.none { it.kind == SpeechMap.Kind.Math })

            val eta = map.spans.first { it.mathId.endsWith("#eta") }
            val onEta = map.resolve(eta.start..eta.start)
            assertEquals(eta.mathId, onEta.spans.single().mathId)
            assertNotEquals(h1.mathId, onEta.spans.single().mathId)

            val sentence = map.resolve(null)
            assertEquals(true, sentence.sentenceLevel)
            assertTrue(sentence.spans.count { it.kind == SpeechMap.Kind.Math } >= 3)

            val second = paragraph(html, "p2")
            val repeated = checkNotNull(SpeechMap.from(second.locator))
            val repeatedIds = repeated.spans.filter { it.kind == SpeechMap.Kind.Math }.map { it.mathId }
            assertEquals(
                listOf("h3", "eta2", "h4").map { SpeechAnchors.scope(locator.href.toString(), it) },
                repeatedIds
            )
            val backToProse = repeated.spans.last { it.kind == SpeechMap.Kind.Text }
            assertTrue(backToProse.raw.contains("回到中文"))
            val spokenBack = repeated.resolve(backToProse.start..backToProse.start)
            assertEquals(SpeechMap.Kind.Text, spokenBack.spans.single().kind)

            val note = paragraph(html, "p3")
            val noted = checkNotNull(SpeechMap.from(note.locator))
            assertTrue(noted.spans.filter { it.kind == SpeechMap.Kind.Text }.none { it.raw.contains("HIDDENLATEX") })
            val spokenNote = note.text
            val hiddenAt = spokenNote.indexOf("HIDDENLATEX")
            assertTrue("spoken note was [$spokenNote]", hiddenAt >= 0)
            assertEquals(
                SpeechAnchors.scope(locator.href.toString(), "mx"),
                noted.resolve(hiddenAt..hiddenAt).spans.single().mathId
            )
            assertTrue(noted.spans.filter { it.kind == SpeechMap.Kind.Text }.none { it.raw.contains("其中") })

            val split = paragraph(html, "p4")
            val whole = checkNotNull(SpeechMap.from(split.locator))
            val period = split.text.indexOf('。')
            assertTrue(period > 0)
            val left = whole.slice(0, period + 1)
            val right = whole.slice(period + 1, split.text.length)
            val splitId = SpeechAnchors.scope(locator.href.toString(), "split")
            assertTrue(left.spans.all { it.kind == SpeechMap.Kind.Math && it.mathId == splitId })
            assertTrue(right.spans.any { it.kind == SpeechMap.Kind.Math && it.mathId == splitId })

            val sentences = TextContentTokenizer(Language("zh"), TextUnit.Sentence)
                .tokenize(element(html, "p1"))
                .filterIsInstance<TextElement>()
                .flatMap { it.segments }
            var search = 0
            assertTrue(sentences.size > 1)
            for (sentenceSegment in sentences) {
                val at = first.text.indexOf(sentenceSegment.text, search)
                assertTrue(at >= 0)
                val expected = map.slice(at, at + sentenceSegment.text.length)
                val actual = checkNotNull(SpeechMap.from(sentenceSegment.locator))
                assertEquals(
                    expected.spans.map { it.kind to it.mathId to it.selector to it.node },
                    actual.spans.map { it.kind to it.mathId to it.selector to it.node }
                )
                assertTrue(actual.spans.all { it.start >= 0 && it.end <= sentenceSegment.text.length })
                search = at + sentenceSegment.text.length
            }

            val otherHref = Url("/dir/other.xhtml")!!
            val other = paragraph(html, "p1", locator.copy(href = otherHref))
            val otherId = checkNotNull(SpeechMap.from(other.locator)).spans
                .first { it.kind == SpeechMap.Kind.Math }
                .mathId
            assertEquals(SpeechAnchors.scope(otherHref.toString(), "h1"), otherId)
            assertNotEquals(mathIds.first(), otherId)
        } finally {
            MathSpeechLanguage.code = null
        }
    }

    @Test
    fun `formula ids cover the reading window before the rest of the chapter`() = runTest {
        val paragraphs = (0 until 40).joinToString("") { index ->
            """<p id="p$index">文字 <math id="m$index"><mi>x</mi></math> 后</p>"""
        }
        val html = """<html xmlns="http://www.w3.org/1998/xhtml"><body>$paragraphs</body></html>"""
        val iter = iterator(html, locator(selector = "#p20"))
        assertTrue(iter.hasNext())
        val around = iter.identifiedFormulaCount
        assertTrue("identified $around formulas on the first step", around in 1..11)
        while (iter.hasNext()) {
            iter.next()
        }
        assertTrue(iter.identifiedFormulaCount > around)
        assertTrue(iter.identifiedFormulaCount < 40)
    }

    private suspend fun paragraph(
        html: String,
        id: String,
        start: Locator = locator,
    ): Segment =
        element(html, id, start).segments.single()

    private suspend fun element(
        html: String,
        id: String,
        start: Locator = locator,
    ): TextElement =
        iterator(html, start).elements()
            .filterIsInstance<TextElement>()
            .single { it.locator.locations["cssSelector"] == "#$id" }
}
