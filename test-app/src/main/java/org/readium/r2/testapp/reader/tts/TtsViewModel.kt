/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.testapp.reader.MediaService
import org.readium.r2.testapp.reader.MediaServiceFacade
import org.readium.r2.testapp.reader.ReaderInitData
import org.readium.r2.testapp.reader.VisualReaderInitData
import org.readium.r2.testapp.reader.preferences.PreferencesManager
import org.readium.r2.testapp.reader.preferences.UserPreferencesViewModel
import org.readium.r2.testapp.utils.extensions.mapStateIn
import timber.log.Timber

/**
 * View model controlling a [TtsNavigator] to read a publication aloud.
 *
 * Note: This is not an Android ViewModel, but it is a component of ReaderViewModel.
 */
@OptIn(ExperimentalReadiumApi::class, ExperimentalCoroutinesApi::class)
class TtsViewModel private constructor(
    private val viewModelScope: CoroutineScope,
    private val bookId: Long,
    private val publication: Publication,
    private val ttsNavigatorFactory: AndroidTtsNavigatorFactory,
    private val mediaServiceFacade: MediaServiceFacade,
    private val preferencesManager: PreferencesManager<AndroidTtsPreferences>,
    private val highlightColorStore: TtsHighlightColorStore,
) : TtsNavigator.Listener {

    companion object {
        /**
         * Returns an instance of [TtsViewModel] if the given [publication] can be played with the
         * TTS engine.
         */
        operator fun invoke(
            viewModelScope: CoroutineScope,
            readerInitData: ReaderInitData,
        ): TtsViewModel? {
            if (readerInitData !is VisualReaderInitData || readerInitData.ttsInitData == null) {
                return null
            }

            return TtsViewModel(
                viewModelScope = viewModelScope,
                bookId = readerInitData.bookId,
                publication = readerInitData.publication,
                ttsNavigatorFactory = readerInitData.ttsInitData.navigatorFactory,
                mediaServiceFacade = readerInitData.ttsInitData.mediaServiceFacade,
                preferencesManager = readerInitData.ttsInitData.preferencesManager,
                highlightColorStore = readerInitData.ttsInitData.highlightColorStore
            )
        }
    }

    sealed class Event {
        /**
         * Emitted when the [TtsNavigator] fails with an error.
         */
        class OnError(val error: TtsError) : Event()

        /**
         * Emitted when the selected language cannot be played because it is missing voice data.
         */
        class OnMissingVoiceData(val language: Language) : Event()
    }

    @Suppress("Unchecked_cast")
    private val MediaService.Session.ttsNavigator
        get() = navigator as? AndroidTtsNavigator

    private val navigatorNow: AndroidTtsNavigator? get() =
        mediaServiceFacade.session.value?.ttsNavigator

    private var launchJob: Job? = null

private var visualNavigator: VisualNavigator? = null

    /**
     * 🌟 显式绑定当前的 VisualNavigator（供 ReaderFragment 或 ReaderViewModel 在视图就绪时调用）
     */
    fun bindVisualNavigator(navigator: VisualNavigator) {
        this.visualNavigator = navigator
        Timber.i("VisualNavigator successfully bound to TtsViewModel: $navigator")
    }

    /**
     * 当阅读器 Fragment 销毁或页面切换时解绑，防止内存泄漏
     */
    fun unbindVisualNavigator() {
        this.visualNavigator = null
        Timber.i("VisualNavigator unbound from TtsViewModel")
    }
    
    // 🌟 记录当前章节最后一次合法的有效 progression（防止脏数据导致倒退跳页）
    private var lastValidProgression: Double = 0.0
    
    // 🌟 记录上一个句子的章节纯净路径（不依赖 visualNavigator 引用）
    private var lastChapterPath: String? = null

    // 🌟 终极高亮保底引擎（JavaScript）
// 🌟 将 JS 模板里的颜色改为动态参数
    private fun getFallbackJsScript(cssColor: String): String = """
(function() {
    let style = document.getElementById('voxread-style');
    if (!style) {
        style = document.createElement('style');
        style.id = 'voxread-style';
        document.head.appendChild(style);
    }
    // 🌟 动态更新用户当前配置的颜色
    style.innerHTML = `
        .voxread-force-highlight {
            background-color: $cssColor !important;
            mix-blend-mode: multiply !important;
            border-radius: 4px !important;
            outline: 2px solid $cssColor !important;
            display: block !important;
        }
        math.voxread-force-highlight {
            display: inline-block !important;
            background-color: $cssColor !important;
            border-radius: 4px !important;
        }
    `;

    window.voxReadForceHighlight = function(highlightText, cssSelector) {
        // 清理旧高亮
        document.querySelectorAll('.voxread-force-highlight').forEach(el => {
            el.classList.remove('voxread-force-highlight');
        });

        const raw = (highlightText || '').trim();
        let targetElement = null;

        // 策略 1：选择器匹配
        if (cssSelector) {
            try {
                const el = document.querySelector(cssSelector);
                if (el && el !== document.body && el !== document.documentElement) {
                    targetElement = el;
                }
            } catch(e) {}
        }

        // 策略 2：文本关键字匹配
        if (!targetElement && raw) {
            const words = raw.match(/[\u4e00-\u9fa5]{1,}|[a-zA-Z]{3,}|lim/g) || [];
            for (let word of words) {
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                let node;
                while (node = walker.nextNode()) {
                    if (node.nodeValue && node.nodeValue.includes(word)) {
                        targetElement = node.parentElement;
                        break;
                    }
                }
                if (targetElement) break;
            }
        }

        // 策略 3：MathML 元素遍历
        if (!targetElement) {
            const mathElements = document.querySelectorAll('math');
            for (let m of mathElements) {
                if (m.textContent && (m.textContent.includes('lim') || m.textContent.includes('∈'))) {
                    targetElement = m;
                    break;
                }
            }
        }

        if (targetElement) {
            let curr = targetElement;
            while (curr && curr !== document.body) {
                const tag = curr.tagName.toLowerCase();
                if (['p', 'div', 'li', 'section'].includes(tag)) {
                    break;
                }
                curr = curr.parentElement || curr.parentNode;
            }
            const container = (curr && curr !== document.body) ? curr : targetElement;
            container.classList.add('voxread-force-highlight');
            return true;
        }
        return false;
    };
})();
""".trimIndent()

    private val _events: Channel<Event> =
        Channel(Channel.BUFFERED)

    val events: Flow<Event> =
        _events.receiveAsFlow()

    val preferencesModel: UserPreferencesViewModel<AndroidTtsSettings, AndroidTtsPreferences> =
        UserPreferencesViewModel(
            viewModelScope = viewModelScope,
            bookId = bookId,
            preferencesManager = preferencesManager
        ) { preferences ->
            val baseEditor = ttsNavigatorFactory.createPreferencesEditor(preferences)
            val voices = navigatorNow?.voices.orEmpty()
            TtsPreferencesEditor(baseEditor, voices)
        }

    val showControls: StateFlow<Boolean> =
        mediaServiceFacade.session.mapStateIn(viewModelScope) {
            it != null
        }

    val isPlaying: StateFlow<Boolean> =
        mediaServiceFacade.session.flatMapLatest { session ->
            session?.navigator?.playback?.map { playback -> playback.playWhenReady }
                ?: MutableStateFlow(false)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

val position: StateFlow<Locator?> =
        mediaServiceFacade.session.flatMapLatest { session ->
            session?.navigator?.currentLocator?.map { locator ->
                sanitizePositionLocator(locator)
            } ?: MutableStateFlow(null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

val highlight: StateFlow<Locator?> =
        mediaServiceFacade.session.flatMapLatest { session ->
            session?.ttsNavigator?.location?.map { location ->
                val locator = location.utteranceLocator
                sanitizeTtsHighlightLocator(locator)
            } ?: MutableStateFlow(null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val highlightColor: StateFlow<TtsHighlightColor> =
        highlightColorStore.color

    fun setHighlightColor(color: TtsHighlightColor) {
        highlightColorStore.setColor(color)
    }

    init {
        mediaServiceFacade.session
            .flatMapLatest { it?.navigator?.playback ?: MutableStateFlow(null) }
            .onEach { playback ->
                when (val state = (playback?.state as? TtsNavigator.State)) {
                    null, TtsNavigator.State.Ready -> {
                        // Do nothing
                    }
                    is TtsNavigator.State.Ended -> {
                        stop()
                    }
                    is TtsNavigator.State.Failure -> {
                        onPlaybackError(state.error)
                        stop()
                    }
                }
            }
            .launchIn(viewModelScope)

        preferencesManager.preferences
            .onEach { navigatorNow?.submitPreferences(it) }
            .launchIn(viewModelScope)

            // 🌟 用户在设置菜单里切换高亮颜色时，立即刷新 WebView 里的颜色
        highlightColor
            .onEach { newColor ->
                val cssColor = getCssHighlightColor(newColor)
                visualNavigator?.runJs(
                    "const st = document.getElementById('voxread-style'); if (st) { ${getFallbackJsScript(cssColor)} }"
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * 将工程中的 TtsHighlightColor 转换为 CSS rgba 字符串
     */
    private fun getCssHighlightColor(color: TtsHighlightColor): String {
        // 如果你的 TtsHighlightColor 是枚举或包装类（通常包含 colorRes 或 colorInt）
        // 比如在 Readium 官方 test-app 中，TtsHighlightColor 类似 Red, Green, Blue, Yellow 等
        // 或者包装了 @ColorInt
        val hexOrName = color.name.lowercase()
        return when {
            hexOrName.contains("yellow") -> "rgba(255, 220, 40, 0.45)"
            hexOrName.contains("red") -> "rgba(244, 67, 54, 0.40)"
            hexOrName.contains("green") -> "rgba(76, 175, 80, 0.40)"
            hexOrName.contains("blue") -> "rgba(33, 150, 243, 0.40)"
            hexOrName.contains("purple") -> "rgba(156, 39, 176, 0.40)"
            else -> "rgba(255, 220, 40, 0.45)" // 兜底默认色
        }
    }

    private fun clearFallbackHighlightInWebView() {
        visualNavigator?.runJs(
            "document.querySelectorAll('.voxread-force-highlight').forEach(el => el.classList.remove('voxread-force-highlight'));"
        )
    }
    
    /**
 * 判定一个 Locator 是否需要触发降级（包含公式、特殊符号、特殊混排）
 */
private fun shouldFallbackToElement(locator: Locator?): Boolean {
    if (locator == null) return false

    val highlightText = locator.text.highlight.orEmpty()
    val other = locator.locations.otherLocations
    val cssSelector = other["cssSelector"] as? String ?: ""

    return other["isMath"] == "true" || other["isMath"] == true ||
            cssSelector.contains(Regex("(?i)math|katex|mathml|latex")) ||
            // 匹配常见公式符号、上下标、特殊排版符号以及希腊字母
            highlightText.contains(Regex("""[=≠≤≥≈±+\-*/∫∑√_{}\^\\\[\]]|[\u0370-\u03FF]"""))
}

private fun fallbackIfFormulaOrBroken(locator: Locator?): Locator? {
    if (locator == null) return null

    val highlightText = locator.text.highlight.orEmpty()
    val other = locator.locations.otherLocations
    val cssSelector = other["cssSelector"] as? String

    // 1. 如果连 cssSelector 都没有，千万不要清空 text！
    // 否则既没有元素选择器，又没有文字，前端必死
    if (cssSelector.isNullOrBlank()) {
        return locator
    }

    // 2. 扩大特征识别范围（加入不可见空白符、特殊标点与常见教材格式）
    val isBrokenRisk = other["isMath"] == "true" || other["isMath"] == true ||
            cssSelector.contains(Regex("(?i)math|katex|mathml|latex")) ||
            highlightText.contains(Regex("""[=≠≤≥≈±+\-*/∫∑√_{}\^\\\[\]]|[\u0370-\u03FF]|[\u00A0\u2000-\u200B]"""))

    if (isBrokenRisk) {
        // 如果底层 JS 允许空 text 高亮元素，清空 text；
        // 如果底层 JS 强制要求 text，可以尝试保留纯净片段，或者直接通过 cssSelector 标记
        return locator.copy(
            text = Locator.Text()
        )
    }

    return locator
}

/**
     * 🌟 将 TTS Locator 清洗为 Readium 原生能够渲染的高亮定位：
     * 如果遇到包含数学公式或 MathML 的句子，剥离会导致 Range 计算失败的 text，
     * 并将选择器指向包含该公式的元素/段落，让底层直接渲染整块高亮。
     */
    private fun sanitizeTtsHighlightLocator(locator: Locator?): Locator? {
        if (locator == null) return null

        val text = locator.text.highlight.orEmpty()
        val other = locator.locations.otherLocations
        val rawSelector = other["cssSelector"] as? String

        // 判断是否是数学公式相关
        val isFormula = other["isMath"] == "true" || other["isMath"] == true ||
                rawSelector?.contains(Regex("(?i)math|katex|mathml")) == true ||
                text.contains(Regex("""[=≠≤≥≈±+\-*/∫∑√_{}\^\\\[\]∈∉⊆⊂lim]"""))

        return if (isFormula) {
            // 构造一个安全的选择器：优先使用已有的选择器，若没有则指向包含公式的通用标签
            val safeSelector = when {
                !rawSelector.isNullOrBlank() -> rawSelector
                else -> "math, .katex, .MathJax"
            }

            val newOtherLocations = other.toMutableMap().apply {
                put("cssSelector", safeSelector)
            }

            // 🌟 核心：置空 text，保留位置和选择器
            // 这样 Readium 的 decorations.js 会把 safeSelector 对应的元素作为一个原子节点进行高亮，
            // 避开了对 MathML 内部文本 Range 的扣取失败！
            locator.copy(
                text = Locator.Text(),
                locations = locator.locations.copy(
                    otherLocations = newOtherLocations
                )
            )
        } else {
            locator
        }
    }

private fun sanitizePositionLocator(locator: Locator?): Locator? {
        if (locator == null) return null

        val text = locator.text.highlight.orEmpty()
        val other = locator.locations.otherLocations
        val rawSelector = other["cssSelector"] as? String

        val isFormula = other["isMath"] == "true" || other["isMath"] == true ||
                rawSelector?.contains(Regex("(?i)math|katex|mathml")) == true ||
                text.contains(Regex("""[=≠≤≥≈±+\-*/∫∑√_{}\^\\\[\]∈∉⊆⊂lim]"""))

        // 🌟 只要是公式，position 绝对返回 null！
        // 这样 VisualNavigator 就不会去触发 go() 翻页，画面就会保持在当前页！
        if (isFormula) {
            return null
        }

        // 常规句子的防倒退逻辑保持不变
        val targetPath = locator.href.toString().substringBefore('#').trimStart('/')
        val currentVisualPath = visualNavigator?.currentLocator?.value?.href
            ?.toString()?.substringBefore('#')?.trimStart('/')

        val isSameChapter = (currentVisualPath != null && currentVisualPath == targetPath) ||
                            (lastChapterPath != null && lastChapterPath == targetPath)

        if (!isSameChapter) {
            lastChapterPath = targetPath
            lastValidProgression = 0.0
            return locator
        }

        val currentProgression = locator.locations.progression
        if (currentProgression == null || currentProgression == 0.0) {
            if (lastValidProgression > 0.05) return null
        } else {
            if (currentProgression < lastValidProgression - 0.01) return null
            lastValidProgression = currentProgression
        }

        return locator
    }

/**
     * Starts the TTS using the first visible locator in the given [navigator].
     */
    fun start(navigator: Navigator) {
        // 🌟 关键修复：在这里立即绑定当前正在阅读的 visualNavigator
        (navigator as? VisualNavigator)?.let {
            this.visualNavigator = it
            Timber.i("VisualNavigator bound in start(): $it")
        }

        if (launchJob != null) {
            return
        }

        launchJob = viewModelScope.launch {
            openSession(navigator)
        }
    }

    private suspend fun openSession(navigator: Navigator) {
        val start = (navigator as? VisualNavigator)?.firstVisibleElementLocator()

        val ttsNavigator = ttsNavigatorFactory.createNavigator(
            this,
            initialLocator = start,
            initialPreferences = preferencesManager.preferences.value
        ).getOrElse {
            val error = TtsError.Initialization(it)
            _events.send(Event.OnError(error))
            launchJob = null
            return
        }

        try {
            mediaServiceFacade.openSession(bookId, ttsNavigator)
        } catch (e: Exception) {
            ttsNavigator.close()
            val error = TtsError.ServiceError(e)
            _events.trySend(Event.OnError(error))
            launchJob = null
            return
        }

        ttsNavigator.play()
    }

    /**
     * 🌟 在 WebView 中执行强制高亮：
     * 无论 Readium 原生高亮是否成功、无论公式是否导致 Range 碎裂，
     * 直接在 DOM 层找到最外层段落元素打上高亮，保证 100% 不漏高亮！
     */
    // private fun applyFallbackHighlightInWebView(locator: Locator?) {
    //     val navigator = visualNavigator ?: return
    //     if (locator == null) {
    //         navigator.evaluateJavascript(
    //             "document.querySelectorAll('.voxread-force-highlight').forEach(el => el.classList.remove('voxread-force-highlight'));"
    //         )
    //         return
    //     }

    //     val textSnippet = locator.text.highlight
    //         ?.replace("\\", "\\\\")
    //         ?.replace("'", "\\'")
    //         ?.replace("\"", "\\\"")
    //         ?.replace("\n", " ")
    //         .orEmpty()

    //     val selector = (locator.locations.otherLocations["cssSelector"] as? String)
    //         ?.replace("\\", "\\\\")
    //         ?.replace("'", "\\'")
    //         .orEmpty()

    //     // 合并注入样式与调用：第一次调用时会自动初始化样式和函数
    //     val jsCode = """
    //         $VOXREAD_FALLBACK_JS
    //         window.voxReadForceHighlight && window.voxReadForceHighlight('$textSnippet', '$selector');
    //     """.trimIndent()

    //     navigator.evaluateJavascript(jsCode)
    // }

    private fun VisualNavigator?.runJs(javascript: String) {
        val nav = this ?: return
        try {
            val method = nav.javaClass.getMethod("evaluateJavascript", String::class.java)
            method.invoke(nav, javascript)
        } catch (e: Exception) {
            Timber.w(e, "Unable to evaluate javascript on visualNavigator")
        }
    }

private fun applyFallbackHighlightInWebView(locator: Locator?) {
        val navigator = visualNavigator ?: return
        if (locator == null) {
            navigator.runJs(
                "document.querySelectorAll('.voxread-force-highlight').forEach(el => el.classList.remove('voxread-force-highlight'));"
            )
            return
        }

        // 🌟 动态获取当前配置的高亮颜色并转为 CSS 格式
        val currentColor = highlightColor.value
        val cssColor = getCssHighlightColor(currentColor)

        val textSnippet = locator.text.highlight
            ?.replace("\\", "\\\\")
            ?.replace("'", "\\'")
            ?.replace("\"", "\\\"")
            ?.replace("\n", " ")
            .orEmpty()

        val selector = (locator.locations.otherLocations["cssSelector"] as? String)
            ?.replace("\\", "\\\\")
            ?.replace("'", "\\'")
            .orEmpty()

        // 🌟 注入带用户自定义颜色的脚本
        val jsCode = """
            ${getFallbackJsScript(cssColor)}
            window.voxReadForceHighlight && window.voxReadForceHighlight('$textSnippet', '$selector');
        """.trimIndent()

        navigator.runJs(jsCode)
    }

fun stop() {
        launchJob = null
        lastValidProgression = 0.0
        visualNavigator?.runJs(
            "document.querySelectorAll('.voxread-force-highlight').forEach(el => el.classList.remove('voxread-force-highlight'));"
        )
        mediaServiceFacade.closeSession()
    }

    fun play() {
        navigatorNow?.play()
    }

    fun pause() {
        navigatorNow?.pause()
    }

    fun previous() {
        navigatorNow?.skipToPreviousUtterance()
    }

    fun next() {
        navigatorNow?.skipToNextUtterance()
    }

    override fun onStopRequested() {
        stop()
    }

    private fun onPlaybackError(error: TtsNavigator.Error) {
        val event = when (error) {
            is TtsNavigator.Error.ContentError -> {
                Event.OnError(TtsError.ContentError(error))
            }
            is TtsNavigator.Error.EngineError<*> -> {
                val engineError = (error.cause as AndroidTtsEngine.Error)
                when (engineError) {
                    is AndroidTtsEngine.Error.LanguageMissingData ->
                        Event.OnMissingVoiceData(engineError.language)
                    is AndroidTtsEngine.Error.Network -> {
                        val ttsError = TtsError.EngineError.Network(engineError)
                        Event.OnError(ttsError)
                    }
                    else -> {
                        val ttsError = TtsError.EngineError.Other(engineError)
                        Event.OnError(ttsError)
                    }
                }.also { Timber.e("Error type: $error") }
            }
        }

        viewModelScope.launch {
            _events.send(event)
        }
    }
}
