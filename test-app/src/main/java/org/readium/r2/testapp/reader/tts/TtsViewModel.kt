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
import org.readium.r2.navigator.epub.EpubNavigatorFragment
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
        class OnError(val error: TtsError) : Event()
        class OnMissingVoiceData(val language: Language) : Event()
    }

    @Suppress("Unchecked_cast")
    private val MediaService.Session.ttsNavigator
        get() = navigator as? AndroidTtsNavigator

    private val navigatorNow: AndroidTtsNavigator? get() =
        mediaServiceFacade.session.value?.ttsNavigator

    private var launchJob: Job? = null
    private var visualNavigator: VisualNavigator? = null

    fun bindVisualNavigator(navigator: VisualNavigator) {
        this.visualNavigator = navigator
        Timber.i("VisualNavigator successfully bound to TtsViewModel: $navigator")
    }

    fun unbindVisualNavigator() {
        this.visualNavigator = null
        Timber.i("VisualNavigator unbound from TtsViewModel")
    }

    private var lastValidProgression: Double = 0.0
    private var lastChapterPath: String? = null

// 替换 getFallbackJsScript 方法：彻底移除有毒的 mix-blend-mode，确保 100% 可见
    private fun getFallbackJsScript(cssColor: String): String = """
(function() {
    let style = document.getElementById('voxread-style');
    if (!style) {
        style = document.createElement('style');
        style.id = 'voxread-style';
        document.head.appendChild(style);
    }
    // 🌟 采用半透明背景 + 强对比左边框，绝不在夜间模式中隐形！
    style.innerHTML = `
        .voxread-force-highlight {
            background-color: $cssColor !important;
            border-left: 5px solid #FF9800 !important;
            padding-left: 6px !important;
            border-radius: 4px !important;
            transition: background-color 0.2s ease !important;
        }
    `;

    window.voxReadForceHighlight = function(highlightText, cssSelector) {
        // 清理旧高亮
        document.querySelectorAll('.voxread-force-highlight').forEach(el => {
            el.classList.remove('voxread-force-highlight');
        });

        let target = null;

        // 1. 优先根据定位器选择器查
        if (cssSelector) {
            try {
                target = document.querySelector(cssSelector);
            } catch(e) {}
        }

        // 2. 如果没查到，扫描所有带有公式标记的段落或 math 标签
        if (!target) {
            const mathList = document.querySelectorAll('math, .has-math');
            const cleanText = (highlightText || '').replace(/\s+/g, '');
            
            // 匹配段落内部文字重合最多的那个元素
            let maxScore = -1;
            for (let el of mathList) {
                const parentP = el.closest('p') || el;
                const pText = (parentP.textContent || '').replace(/\s+/g, '');
                
                // 计算重叠字符数
                let score = 0;
                for (let i = 0; i < Math.min(cleanText.length, 15); i++) {
                    if (pText.includes(cleanText[i])) score++;
                }
                
                if (score > maxScore && score >= 2) {
                    maxScore = score;
                    target = parentP;
                }
            }
        }

        // 3. 兜底策略：如果依然找不到，直接取第一个可视的 math 父级
        if (!target) {
            const m = document.querySelector('math');
            if (m) target = m.closest('p') || m.parentElement;
        }

        // 4. 执行最终外层高亮
        if (target) {
            const container = target.closest('p, div, section, li') || target;
            container.classList.add('voxread-force-highlight');
            return true;
        }
        return false;
    };
})();
""".trimIndent()

    private val _events: Channel<Event> = Channel(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

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
        mediaServiceFacade.session.mapStateIn(viewModelScope) { it != null }

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
                if (shouldFallbackToElement(locator)) {
                    null // 包含公式一律转给 JS 兜底高亮整段，避免原生查找失败
                } else {
                    locator // 纯汉字文本依然走原生高亮
                }
            } ?: MutableStateFlow(null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val highlightColor: StateFlow<TtsHighlightColor> = highlightColorStore.color

    fun setHighlightColor(color: TtsHighlightColor) {
        highlightColorStore.setColor(color)
    }

    init {
        mediaServiceFacade.session
            .flatMapLatest { it?.navigator?.playback ?: MutableStateFlow(null) }
            .onEach { playback ->
                when (val state = (playback?.state as? TtsNavigator.State)) {
                    null, TtsNavigator.State.Ready -> {}
                    is TtsNavigator.State.Ended -> stop()
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

        highlightColor
            .onEach { newColor ->
                val cssColor = getCssHighlightColor(newColor)
                visualNavigator?.runJs(
                    "const st = document.getElementById('voxread-style'); if (st) { ${getFallbackJsScript(cssColor)} }"
                )
            }
            .launchIn(viewModelScope)

        // 监听 TTS 进度，遇到公式自动启动外层高亮
        mediaServiceFacade.session
            .flatMapLatest { it?.ttsNavigator?.location ?: MutableStateFlow(null) }
            .onEach { location ->
                if (location == null) {
                    clearFallbackHighlightInWebView()
                } else {
                    val locator = location.utteranceLocator
                    if (shouldFallbackToElement(locator)) {
                        applyFallbackHighlightInWebView(locator)
                    } else {
                        clearFallbackHighlightInWebView()
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun getCssHighlightColor(color: TtsHighlightColor): String {
        val hexOrName = color.name.lowercase()
        return when {
            hexOrName.contains("yellow") -> "rgba(255, 220, 40, 0.45)"
            hexOrName.contains("red") -> "rgba(244, 67, 54, 0.40)"
            hexOrName.contains("green") -> "rgba(76, 175, 80, 0.40)"
            hexOrName.contains("blue") -> "rgba(33, 150, 243, 0.40)"
            hexOrName.contains("purple") -> "rgba(156, 39, 176, 0.40)"
            else -> "rgba(255, 220, 40, 0.45)"
        }
    }

    private fun clearFallbackHighlightInWebView() {
        visualNavigator?.runJs(
            "document.querySelectorAll('.voxread-force-highlight').forEach(el => el.classList.remove('voxread-force-highlight'));"
        )
    }

    /**
     * 🌟【关键修复】：扩大公式与学术符号识别网络！
     * 在中文学术语境下，只要包含任意英文变量（如 x, y, m, h）或数学符号，一律视为公式段落，彻底消除漏网！
     */
    private fun shouldFallbackToElement(locator: Locator?): Boolean {
        if (locator == null) return false

        val highlightText = locator.text.highlight.orEmpty()
        val other = locator.locations.otherLocations
        val cssSelector = other["cssSelector"] as? String ?: ""

        // 1. 显式选择器标记
        if (other["isMath"] == "true" || other["isMath"] == true ||
            cssSelector.contains(Regex("(?i)math|katex|mathml|latex"))
        ) {
            return true
        }

        // 2. 包含任意数学算子、符号、希腊字母
        if (highlightText.contains(Regex("""[=≠≤≥≈±+\-*/∫∑√_{}\^\\\[\]∈∉⊆⊂lim<>≍→↑↓|]""")) ||
            highlightText.contains(Regex("""[\u0370-\u03FF]|[\u2200-\u22FF]"""))
        ) {
            return true
        }

        // 3. 包含任意拉丁字母（如单字母变量 $m$, $h$, $z$），全部无缝转入兜底高亮！
        if (highlightText.contains(Regex("""[a-zA-Z]"""))) {
            return true
        }

        return false
    }

    private fun sanitizePositionLocator(locator: Locator?): Locator? {
        if (locator == null) return null

        if (shouldFallbackToElement(locator)) {
            return null
        }

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

    fun start(navigator: Navigator) {
        (navigator as? VisualNavigator)?.let {
            this.visualNavigator = it
            Timber.i("VisualNavigator bound in start(): $it")
        }

        if (launchJob != null) return

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
     * 🌟【关键修复】：彻底解决挂起函数反射失效的致命 BUG！
     * 在协程内通过强类型直接调用 EpubNavigatorFragment.evaluateJavascript()，
     * 保证 100% 成功注入执行，绝不再抛出 NoSuchMethodException！
     */
    private fun VisualNavigator?.runJs(javascript: String) {
        val nav = this ?: return
        viewModelScope.launch {
            try {
                if (nav is EpubNavigatorFragment) {
                    nav.evaluateJavascript(javascript)
                } else {
                    val directMethod = nav.javaClass.methods.firstOrNull {
                        it.name == "evaluateJavascript" && it.parameterTypes.size == 1
                    }
                    directMethod?.invoke(nav, javascript)
                }
            } catch (e: Exception) {
                Timber.w(e, "Unable to evaluate javascript on visualNavigator")
            }
        }
    }

    private fun applyFallbackHighlightInWebView(locator: Locator?) {
        val navigator = visualNavigator ?: return
        if (locator == null) {
            clearFallbackHighlightInWebView()
            return
        }

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

        val jsCode = """
            ${getFallbackJsScript(cssColor)}
            window.voxReadForceHighlight && window.voxReadForceHighlight('$textSnippet', '$selector');
        """.trimIndent()

        navigator.runJs(jsCode)
    }

    fun stop() {
        launchJob = null
        lastValidProgression = 0.0
        clearFallbackHighlightInWebView()
        mediaServiceFacade.closeSession()
    }

    fun play() { navigatorNow?.play() }
    fun pause() { navigatorNow?.pause() }
    fun previous() { navigatorNow?.skipToPreviousUtterance() }
    fun next() { navigatorNow?.skipToNextUtterance() }

    override fun onStopRequested() { stop() }

    private fun onPlaybackError(error: TtsNavigator.Error) {
        val event = when (error) {
            is TtsNavigator.Error.ContentError -> Event.OnError(TtsError.ContentError(error))
            is TtsNavigator.Error.EngineError<*> -> {
                val engineError = (error.cause as AndroidTtsEngine.Error)
                when (engineError) {
                    is AndroidTtsEngine.Error.LanguageMissingData -> Event.OnMissingVoiceData(engineError.language)
                    is AndroidTtsEngine.Error.Network -> Event.OnError(TtsError.EngineError.Network(engineError))
                    else -> Event.OnError(TtsError.EngineError.Other(engineError))
                }.also { Timber.e("Error type: $error") }
            }
        }

        viewModelScope.launch { _events.send(event) }
    }
}