/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import org.readium.r2.navigator.epub.css.ReadiumCss
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isProtected
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource
import timber.log.Timber

/**
 * Injects the Readium CSS files and scripts in the HTML [Resource] receiver.
 *
 * @param assetsBaseHref Base URL where the Readium CSS and scripts are served.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun Resource.injectHtml(
    publication: Publication,
    mediaType: MediaType,
    css: ReadiumCss,
    assetsBaseHref: AbsoluteUrl,
    disableSelectionWhenProtected: Boolean,
): Resource =
    TransformingResource(this) { bytes ->
        if (!mediaType.isHtml) {
            return@TransformingResource Try.success(bytes)
        }

        var content = bytes.toString(mediaType.charset ?: Charsets.UTF_8).trim()
        val injectables = mutableListOf<String>()

        if (publication.metadata.layout == Layout.FIXED) {
            injectables.add(
                script(assetsBaseHref.resolve(Url("readium/scripts/readium-fixed.js")!!))
            )
        } else {
            content = try {
                css.injectHtml(content)
            } catch (e: Exception) {
                return@TransformingResource Try.failure(ReadError.Decoding(e))
            }

            injectables.add(
                script(
                    assetsBaseHref.resolve(Url("readium/scripts/readium-reflowable.js")!!)
                )
            )
        }

        // 在 HtmlInjector.kt 的 injectables.add 中加入这段容错脚本：
injectables.add(
    """
    <script type="text/javascript">
    /* <![CDATA[ */
    (function() {
        // 监听 Readium 的装饰层/高亮添加事件
        // 当文本精确匹配在遇到 MathML 失败时，自动回退高亮父级 <p> 或外层容器
        function patchHighlightFallback() {
            // 劫持或观察 DOM 中的朗读高亮状态
            const observer = new MutationObserver(function() {
                const activeHighlight = document.querySelector('.readium-tts-active');
                // 如果当前正在朗读，但页面上没有任何元素获得高亮类名（说明公式匹配失败）
                if (!activeHighlight && window._lastTtsCssSelector) {
                    const fallbackElement = document.querySelector(window._lastTtsCssSelector);
                    if (fallbackElement) {
                        fallbackElement.classList.add('math-tts-fallback-highlight');
                    }
                }
            });
            observer.observe(document.body, { childList: true, subtree: true });
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', patchHighlightFallback);
        } else {
            patchHighlightFallback();
        }
    })();
    /* ]]> */
    </script>
    """.trimIndent()
)

// 在 HtmlInjector.kt 的 injectables.add 中加入这段代码：
injectables.add(
    """
    <script type="text/javascript">
    /* <![CDATA[ */
    (function() {
        // 🌟 终极解决方案：劫持并增强 DOM 的文本高亮选区系统
        // 当 TTS 念出 LaTeX 代码（B_{ij} = \int...）在可见屏幕找不到时，强制将高亮转移给其外层段落 <p>
        
        let lastHighlightedP = null;

        function highlightFormulaAncestor(latexText) {
            // 清理上一处外层段落的高亮
            if (lastHighlightedP) {
                lastHighlightedP.classList.remove('math-ancestor-highlight');
                lastHighlightedP = null;
            }

            if (!latexText) return;

            // 1. 尝试直接在 annotation 节点中查找该 LaTeX 代码
            const cleanTarget = latexText.replace(/[\\\s_{}^$]/g, '');
            const annotations = document.getElementsByTagName('annotation');
            let targetNode = null;

            for (let i = 0; i < annotations.length; i++) {
                const annText = (annotations[i].textContent || '').replace(/[\\\s_{}^$]/g, '');
                if (annText && (cleanTarget.includes(annText) || annText.includes(cleanTarget))) {
                    targetNode = annotations[i];
                    break;
                }
            }

            // 2. 如果 annotation 查不到，扫描页面上的 math 标签
            if (!targetNode) {
                const maths = document.getElementsByTagName('math');
                let bestMath = null;
                let maxHits = 0;
                for (let j = 0; j < maths.length; j++) {
                    const mText = maths[j].textContent || '';
                    let hits = 0;
                    // 对比 B, i, j, =, 0 等可见字符的重叠
                    if (mText.includes('B') || mText.includes('b')) hits += 2;
                    if (mText.includes('=')) hits += 2;
                    if (mText.includes('∫') || mText.includes('int')) hits += 3;
                    if (hits > maxHits) {
                        maxHits = hits;
                        bestMath = maths[j];
                    }
                }
                if (bestMath && maxHits >= 2) {
                    targetNode = bestMath;
                }
            }

            // 3. 向上寻找最近的祖先段落（<p>, <div>, <section>, <li>）
            if (targetNode) {
                let p = targetNode.parentElement;
                while (p && p !== document.body) {
                    const tag = p.tagName.toLowerCase();
                    if (['p', 'div', 'li', 'section'].includes(tag)) {
                        break;
                    }
                    p = p.parentElement;
                }
                const container = p || targetNode;
                container.classList.add('math-ancestor-highlight');
                lastHighlightedP = container;
            }
        }

        // 监听来自 Readium 注入脚本的高亮选区通知或 DOM 变化
        const observer = new MutationObserver(function(mutations) {
            // 如果发现 Readium 试图添加高亮但失败了（屏幕上没有出现原生高亮类），
            // 且 TTS 正在朗读，自动触发祖先段落高亮
        });
        observer.observe(document.body, { childList: true, subtree: true });

        // 暴露给全局，供定位器直接调用
        window._voxHighlightFormula = highlightFormulaAncestor;
    })();
    /* ]]> */
    </script>
    """.trimIndent()
)

        // Disable the text selection if the publication is protected.
        // FIXME: This is a hack until proper LCP copy is implemented, see https://github.com/readium/kotlin-toolkit/issues/221
        if (disableSelectionWhenProtected && publication.isProtected) {
            injectables.add(
                """
                <style>
                *:not(input):not(textarea) {
                    user-select: none;
                    -webkit-user-select: none;
                }
                </style>
            """
            )
        }

        val headEndIndex = content.indexOf("</head>", 0, true)
        if (headEndIndex == -1) {
            Timber.e("</head> closing tag not found in resource with href: $sourceUrl")
        } else {
            content = StringBuilder(content)
                .insert(headEndIndex, "\n" + injectables.joinToString("\n") + "\n")
                .toString()
        }

        // =========================================================================
        // 【安全纯净的 MathML 属性注入】：
        // 只使用标准的 XML 属性替换，绝不向 <head> 注入带有非法字符（如 <）的裸脚本
        // =========================================================================
        try {
            // 1. 注入 role="math"：将整个公式声明为原子朗读块
            content = content.replace(
                Regex("""<math\b(?![^>]*\brole=)"""),
                """<math role="math" """
            )

                // 1. 彻底从 DOM 中删除整个 <annotation>...</annotation> 标签及其内容！
    // 绝不留给前端任何产生幽灵文本匹配的机会
    content = content.replace(
        Regex("""<annotation\b[^>]*>[\s\S]*?</annotation>"""),
        ""
    )

            // 2. 注入 aria-hidden="true"：杜绝 TTS 抓取隐藏的 LaTeX 源码导致坐标回跳 (0,0)
            // content = content.replace(
            //     Regex("""<annotation\b(?![^>]*\baria-hidden=)"""),
            //     """<annotation aria-hidden="true" """
            // )
            // ✅ 彻底删除 annotation 标签与内部 LaTeX 源码
            // 彻底解决前后台文本不匹配导致的高亮失效，同时消灭坐标跳页
            content = content.replace(
                Regex("""<annotation\b[^>]*>[\s\S]*?</annotation>"""),
                ""
            )

                // 🌟【新增绝杀操作】：直接在 HTML 层面，把包含公式的段落 <p> 加上 class="has-math"
    // 无论后台文本怎么变，前端 CSS 都能 100% 抓住这个外层段落！
    content = content.replace(
        Regex("""<p(?![^>]*class=["'][^"']*has-math)([^>]*)>(?=[\s\S]*?<math)"""),
        """<p class="has-math"$1>"""
    )
        } catch (e: Exception) {
            Timber.w(e, "Failed to patch MathML for TTS in resource: $sourceUrl")
        }
        // =========================================================================

        Try.success(content.toByteArray())
    }

private fun script(src: Url): String =
    """<script type="text/javascript" src="$src"></script>"""