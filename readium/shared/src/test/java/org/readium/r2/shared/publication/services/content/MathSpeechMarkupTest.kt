/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.shared.publication.services.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
class MathSpeechMarkupTest {

    @Test
    fun `power maps each spoken word onto its node`() {
        val bundle = MathSpeechMarkup.align(POWER, powerMath())
        assertEquals("x squared plus y", bundle.spokenText)
        assertEquals(listOf("x"), nodes(bundle, "x"))
        assertEquals(listOf("exp"), nodes(bundle, "squared"))
        assertEquals(listOf("plus"), nodes(bundle, "plus"))
        assertEquals(listOf("y"), nodes(bundle, "y"))
    }

    @Test
    fun `chinese power keeps the exponent on the superscript`() {
        val bundle = MathSpeechMarkup.align(POWER_ZH, powerMath())
        assertEquals("x 平方 加 y", bundle.spokenText)
        assertEquals(listOf("x"), nodes(bundle, "x"))
        assertEquals(listOf("exp"), nodes(bundle, "平方"))
        assertEquals(listOf("plus"), nodes(bundle, "加"))
        assertEquals(listOf("y"), nodes(bundle, "y"))
    }

    @Test
    fun `fraction glue is the fraction not the numerator`() {
        val bundle = MathSpeechMarkup.align(FRACTION, fractionMath())
        assertEquals("a over b", bundle.spokenText)
        assertEquals(listOf("num"), nodes(bundle, "a"))
        assertEquals(listOf("frac"), nodes(bundle, "over"))
        assertEquals(listOf("den"), nodes(bundle, "b"))
    }

    @Test
    fun `chinese fraction speaks the denominator first`() {
        val bundle = MathSpeechMarkup.align(FRACTION_ZH, fractionMath())
        assertEquals("b 分之 a", bundle.spokenText)
        assertEquals(listOf("den"), nodes(bundle, "b"))
        assertEquals(listOf("frac"), nodes(bundle, "分之"))
        assertEquals(listOf("num"), nodes(bundle, "a"))
    }

    @Test
    fun `radical words belong to the root`() {
        val bundle = MathSpeechMarkup.align(ROOT, rootMath())
        assertEquals("the square root of x", bundle.spokenText)
        assertEquals(listOf("root"), nodes(bundle, "square"))
        assertEquals(listOf("radicand"), nodes(bundle, "x"))
        val chinese = MathSpeechMarkup.simplify(MathSpeechMarkup.align(ROOT_ZH, rootMath()))
        assertEquals("根号 x", chinese.spokenText)
        assertEquals(listOf("root"), nodes(chinese, "根号"))
        assertEquals(listOf("radicand"), nodes(chinese, "x"))
    }

    @Test
    fun `repeated variables keep distinct nodes`() {
        val bundle = MathSpeechMarkup.align(REPEAT, repeatMath())
        assertEquals("x plus x", bundle.spokenText)
        val first = nodes(bundle, "x")
        val second = nodesAt(bundle, bundle.spokenText.lastIndexOf('x'))
        assertEquals(listOf("x1"), first)
        assertEquals(listOf("x2"), second)
        assertNotEquals(first, second)
    }

    @Test
    fun `matrix cells and row labels use different nodes`() {
        val bundle = MathSpeechMarkup.align(MATRIX_ZH, matrixMath())
        assertEquals("2 乘 2 矩陣; 列 1; a, b; 列 2; c, d", bundle.spokenText)
        assertEquals(listOf("table"), nodes(bundle, "矩陣"))
        assertEquals(listOf("row1"), nodes(bundle, "列 1"))
        assertEquals(listOf("a"), nodes(bundle, "a"))
        assertEquals(listOf("b"), nodes(bundle, "b"))
        assertEquals(listOf("row2"), nodes(bundle, "列 2"))
        assertEquals(listOf("c"), nodes(bundle, "c"))
        assertEquals(listOf("d"), nodes(bundle, "d"))
    }

    @Test
    fun `a range that covers two words lights both nodes`() {
        val bundle = MathSpeechMarkup.align(POWER_ZH, powerMath())
        val map = SpeechMap(
            listOf(
                SpeechMap.Span(
                    start = 0,
                    end = bundle.spokenText.length,
                    kind = SpeechMap.Kind.Math,
                    selector = "",
                    mathId = "vox:c.xhtml#m",
                    nodes = bundle.anchors,
                    mathml = bundle.canonicalMathMl,
                )
            )
        )
        val start = bundle.spokenText.indexOf('加')
        val highlight = map.resolve(start..bundle.spokenText.lastIndex)
        assertEquals(listOf("plus", "y"), highlight.nodeIds)
    }

    @Test
    fun `utf-16 offsets count a supplementary character as two`() {
        val emoji = "\uD83D\uDE00"
        val parsed = MathSpeechMarkup.parse("<mark name='e'/>$emoji")
        assertEquals(2, parsed.spokenText.length)
        assertEquals(0, parsed.anchors.single().start)
        assertEquals(2, parsed.anchors.single().end)
        assertEquals(listOf("e"), parsed.anchors.single().nodeIds)
    }

    @Test
    fun `placement follows the utterance whitespace`() {
        val spoken = "x 平方"
        val anchors = listOf(MathSpeechAnchor(0, 1, listOf("x")))
        val placed = MathSpeechMarkup.place(" $spoken ", 10, spoken, anchors)
        assertEquals(11, placed.single().start)
        assertEquals(12, placed.single().end)
    }

    private fun nodes(bundle: MathSpeechBundle, word: String): List<String> {
        val start = bundle.spokenText.indexOf(word)
        check(start >= 0) { "missing $word in ${bundle.spokenText}" }
        return nodesAt(bundle, start)
    }

    private fun nodesAt(bundle: MathSpeechBundle, index: Int): List<String> =
        bundle.anchors.filter { index >= it.start && index < it.end }.flatMap { it.nodeIds }

    @Test
    fun `intent subscript id maps back to the presentation node`() {
        val bundle = MathSpeechMarkup.align(
            "<mark name='base'/> L <mark name='sub-indexed-by'/> 下标 <mark name='leaf'/> t",
            """<math><msubsup id="sub"><mi id="base">L</mi><mi id="leaf">t</mi><mn id="exp">2</mn></msubsup></math>""",
        )
        assertEquals(listOf("sub"), nodes(bundle, "下标"))
        assertEquals(listOf("leaf"), nodes(bundle, "t"))
    }

    @Test
    fun `structured fallback keeps the subscript word on the subscript`() {
        val bundle = MathSpeechMarkup.structure(
            """<math><msub id="sub"><mi id="base">L</mi><mi id="leaf">t</mi></msub></math>""",
        )
        assertEquals("L 下标 t", bundle.spokenText)
        assertEquals(listOf("base"), nodes(bundle, "L"))
        assertEquals(listOf("sub"), nodes(bundle, "下标"))
        assertEquals(listOf("leaf"), nodes(bundle, "t"))
        val canonical = bundle.canonicalMathMl
        assertEquals(true, canonical.contains("id=\"sub\"") || canonical.contains("id='sub'"))
    }

    @Test
    fun `structured fallback skips spacing and speaks the relation`() {
        val bundle = MathSpeechMarkup.structure(
            """<math><mrow><mspace width="2.0em"/><mo>≍</mo><mi>τ</mi></mrow></math>""",
        )
        assertEquals("等价于 tau", bundle.spokenText)
        assertEquals(false, bundle.spokenText.contains("qquad"))
        assertEquals(false, bundle.anchors.isEmpty())
    }

    private fun powerMath() = """
        <math id="m"><mrow id="row"><msup id="sup"><mi id="x">x</mi><mn id="exp">2</mn></msup><mo id="plus">+</mo><mi id="y">y</mi></mrow></math>
    """.trim()

    private fun fractionMath() = """
        <math id="m"><mfrac id="frac"><mi id="num">a</mi><mi id="den">b</mi></mfrac></math>
    """.trim()

    private fun rootMath() = """
        <math id="m"><msqrt id="root"><mi id="radicand">x</mi></msqrt></math>
    """.trim()

    private fun repeatMath() = """
        <math id="m"><mrow id="row"><mi id="x1">x</mi><mo id="plus">+</mo><mi id="x2">x</mi></mrow></math>
    """.trim()

    private fun matrixMath() = """
        <math id="m"><mrow id="row"><mo id="open">[</mo><mtable id="table"><mtr id="row1"><mtd><mi id="a">a</mi></mtd><mtd><mi id="b">b</mi></mtd></mtr><mtr id="row2"><mtd><mi id="c">c</mi></mtd><mtd><mi id="d">d</mi></mtd></mtr></mtable><mo id="close">]</mo></mrow></math>
    """.trim()

    private companion object {
        const val POWER =
            "<mark name='x'/> <say-as interpret-as='characters'>x</say-as> <mark name='exp'/> squared  <mark name='plus'/> plus  <mark name='y'/> <say-as interpret-as='characters'>y</say-as>"
        const val POWER_ZH =
            "<mark name='x'/> <say-as interpret-as='characters'>x</say-as> <mark name='exp'/> 平方  <mark name='plus'/> 加  <mark name='y'/> <say-as interpret-as='characters'>y</say-as>"
        const val FRACTION =
            "<mark name='num'/> <say-as interpret-as='characters'>a</say-as> over <mark name='den'/> <say-as interpret-as='characters'>b</say-as> <break time='200ms'/>"
        const val FRACTION_ZH =
            "<mark name='den'/> <say-as interpret-as='characters'>b</say-as> 分之 <mark name='num'/> <say-as interpret-as='characters'>a</say-as>"
        const val ROOT =
            "the square root of <mark name='radicand'/> <say-as interpret-as='characters'>x</say-as> <break time='200ms'/>"
        const val ROOT_ZH =
            "根號 <mark name='radicand'/> <say-as interpret-as='characters'>x</say-as> <break time='200ms'/>"
        const val REPEAT =
            "<mark name='x1'/> <say-as interpret-as='characters'>x</say-as>  <mark name='plus'/> plus  <mark name='x2'/> <say-as interpret-as='characters'>x</say-as>"
        const val MATRIX_ZH =
            "2 乘 2 矩陣 <break time='800ms'/>列 1 <break time='400ms'/> <mark name='a'/> <say-as interpret-as='characters'>a</say-as> <break time='200ms'/> <mark name='b'/> <say-as interpret-as='characters'>b</say-as> <break time='400ms'/>列 2 <break time='400ms'/> <mark name='c'/> <say-as interpret-as='characters'>c</say-as> <break time='200ms'/> <mark name='d'/> <say-as interpret-as='characters'>d</say-as> <break time='800ms'/>"
    }
}
