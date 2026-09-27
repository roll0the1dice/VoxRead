/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader.tts

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.readium.navigator.media.common.MediaNavigator
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
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.SpeechMap
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
import java.util.concurrent.atomic.AtomicLong

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
    private var pageCollectJob: Job? = null
    private var visualNavigator: VisualNavigator? = null
    private var highlightSeq: Int = 0
    private var pendingHref: String? = null
    private var pendingJs: String? = null
    private var lastHighlightKey: String? = null

    fun bindVisualNavigator(navigator: VisualNavigator) {
        attachNavigator(navigator)
        Timber.i("VisualNavigator successfully bound to TtsViewModel: $navigator")
    }

    fun unbindVisualNavigator() {
        pageCollectJob?.cancel()
        pageCollectJob = null
        this.visualNavigator = null
        Timber.i("VisualNavigator unbound from TtsViewModel")
    }

    private fun attachNavigator(navigator: VisualNavigator) {
        if (visualNavigator === navigator && pageCollectJob?.isActive == true) return
        visualNavigator = navigator
        pageCollectJob?.cancel()
        pageCollectJob = viewModelScope.launch {
            navigator.currentLocator
                .map { it.href.toString() }
                .distinctUntilChanged()
                .collect { replayHighlight(it) }
        }
    }

    private fun highlightColorScript(cssColor: String): String {
        val quoted = JSONObject.quote(cssColor)
        return "document.documentElement.style.setProperty('--vox-tts-highlight', $quoted);"
    }

    private fun legacyFormulaScript(locator: Locator, seq: Int): String {
        val other = locator.locations.otherLocations
        val payload = JSONObject()
            .put("href", locator.href.toString())
            .put("seq", seq)
            .put("inline", locator.isFlag("hasInlineMath"))
            .put("mathId", other["mathId"] as? String ?: JSONObject.NULL)
            .put("mathSelector", other["mathSelector"] as? String ?: JSONObject.NULL)
            .put("cssSelector", other["cssSelector"] as? String ?: JSONObject.NULL)
        return "window.voxReadHighlightMath && window.voxReadHighlightMath($payload);"
    }

    private fun speechHighlightScript(
        locator: Locator,
        utterance: String,
        highlight: SpeechMap.Highlight,
        seq: Int,
    ): String {
        val targets = JSONArray()
        highlight.spans.forEach { span ->
            targets.put(
                JSONObject()
                    .put("kind", if (span.kind == SpeechMap.Kind.Math) "math" else "text")
                    .put("selector", span.selector)
                    .put("node", span.node)
                    .put("from", span.from)
                    .put("to", span.to)
                    .put("text", span.raw)
                    .put("mathId", span.mathId)
            )
        }
        val payload = JSONObject()
            .put("href", locator.href.toString())
            .put("seq", seq)
            .put("utterance", utterance)
            .put("sentence", highlight.sentenceLevel)
            .put("targets", targets)
        return "window.voxReadApplyHighlight && window.voxReadApplyHighlight($payload);"
    }

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
            session?.navigator?.currentLocator?.map { followLocator(it) }
                ?: MutableStateFlow(null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val highlight: StateFlow<List<Locator>> =
        mediaServiceFacade.session.flatMapLatest { session ->
            session?.ttsNavigator?.location?.map { location ->
                spokenHighlights(location)
            } ?: MutableStateFlow(emptyList())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val speechSession = AtomicLong(0)
    private val speechSequence = AtomicLong(0)

    /**
     * Latest playback and formula, available as soon as a subscriber collects.
     * The formula comes from the speech map, not from a highlight drawn on the page.
     */
    val speech: StateFlow<TtsSpeechState> =
        mediaServiceFacade.session.flatMapLatest { session ->
            val navigator = session?.ttsNavigator
            if (session == null || navigator == null) {
                flowOf(TtsSpeechState.stopped(speechSequence.incrementAndGet()))
            } else {
                val sessionId = speechSession.incrementAndGet()
                combine(session.navigator.playback, navigator.location) { playback, location ->
                    val spoken = spokenFormula(location)
                    if (spoken.formulaId != null) {
                        val utterance = location.utterance
                        val range = location.range
                        val slice = if (range != null && utterance.isNotEmpty()) {
                            val from = range.first.coerceIn(0, utterance.length)
                            val to = (range.last + 1).coerceIn(from, utterance.length)
                            utterance.substring(from, to)
                        } else {
                            ""
                        }
                        Log.i(
                            "VoxMathSpeech",
                            "range=$range text=${JSONObject.quote(slice)} nodes=${spoken.nodeIds.joinToString(",")}"
                        )
                    }
                    TtsSpeechState(
                        session = sessionId,
                        sequence = speechSequence.incrementAndGet(),
                        play = playOf(playback),
                        chapterHref = location.utteranceLocator.href.toString(),
                        formulaId = spoken.formulaId,
                        activeNodeIds = spoken.nodeIds,
                        utteranceId = utteranceIdOf(location),
                        canonicalMathMl = spoken.mathml,
                    )
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            TtsSpeechState.stopped(speechSequence.incrementAndGet()),
        )

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
                visualNavigator?.runJs(highlightColorScript(getCssHighlightColor(newColor)))
            }
            .launchIn(viewModelScope)

        mediaServiceFacade.session
            .flatMapLatest { it?.ttsNavigator?.location ?: MutableStateFlow(null) }
            .onEach { location -> syncSpeechHighlight(location) }
            .launchIn(viewModelScope)
    }

    private fun getCssHighlightColor(color: TtsHighlightColor): String = color.playingFill()

    private fun clearFormulaHighlight() {
        if (pendingJs == null && pendingHref == null) return
        lastHighlightKey = null
        pendingHref = null
        pendingJs = null
        val seq = ++highlightSeq
        visualNavigator?.runJs("window.voxReadClearHighlight && window.voxReadClearHighlight($seq);")
    }

    private fun syncSpeechHighlight(location: TtsNavigator.Location?) {
        if (location == null) {
            clearFormulaHighlight()
            return
        }
        val utterance = location.utteranceLocator
        val map = SpeechMap.from(utterance)
        if (map != null) {
            val active = map.resolve(location.range)
            if (active.spans.isEmpty()) {
                clearFormulaHighlight()
            } else {
                dispatchHighlight(
                    href = utterance.href.toString(),
                    script = { seq ->
                        speechHighlightScript(utterance, location.utterance, active, seq)
                    }
                )
            }
            return
        }
        if (utterance.isFlag("isMath") || utterance.isFlag("hasInlineMath")) {
            dispatchHighlight(
                href = utterance.href.toString(),
                script = { seq -> legacyFormulaScript(utterance, seq) }
            )
        } else {
            clearFormulaHighlight()
        }
    }

    private fun dispatchHighlight(href: String, script: (Int) -> String) {
        val key = script(0)
        if (key == lastHighlightKey && pendingHref == href) return
        lastHighlightKey = key
        val seq = ++highlightSeq
        val js = highlightColorScript(getCssHighlightColor(highlightColor.value)) + "\n" + script(seq)
        pendingHref = href
        pendingJs = js
        val visible = visualNavigator?.currentLocator?.value?.href?.toString()
        if (visible != null && visible != href) {
            val clearSeq = ++highlightSeq
            visualNavigator?.runJs("window.voxReadClearHighlight && window.voxReadClearHighlight($clearSeq);")
            return
        }
        visualNavigator?.runJs(js) { result ->
            if (result != null && !result.applied()) {
                Timber.w("TTS highlight was not applied on $href (seq=$seq, result=$result)")
            }
        }
    }

    /**
     * Paints the current utterance again, including when the reader is already
     * on that chapter. A stopped session clears the page highlight.
     */
    fun replayVisibleHighlight() {
        if (speech.value.play == TtsPlay.Stopped) {
            clearFormulaHighlight()
            return
        }
        val location = navigatorNow?.location?.value
        if (location == null) {
            clearFormulaHighlight()
            return
        }
        lastHighlightKey = null
        syncSpeechHighlight(location)
    }

    private fun replayHighlight(visibleHref: String) {
        val href = pendingHref ?: return
        val js = pendingJs ?: return
        if (href != visibleHref) {
            val clearSeq = ++highlightSeq
            visualNavigator?.runJs("window.voxReadClearHighlight && window.voxReadClearHighlight($clearSeq);")
            return
        }
        visualNavigator?.runJs(js) { result ->
            if (result != null && !result.applied()) {
                Timber.w("TTS highlight missed after the page became ready: $visibleHref ($result)")
            }
        }
    }

    private fun String.applied(): Boolean =
        trim().trim('"') == "true"

    /**
     * Ordinary words use the same decoration as prose without formulas.
     * The quote is the page text, so the highlighter does not search for the
     * spoken form of a formula. A formula span is drawn by the page script.
     */
    private fun spokenHighlights(location: TtsNavigator.Location): List<Locator> {
        val utterance = location.utteranceLocator
        val map = SpeechMap.from(utterance)
        if (map != null) {
            return textDecorationLocators(utterance, map.resolve(location.range).spans)
        }
        if (utterance.isFlag("isMath") || utterance.isFlag("hasInlineMath")) {
            return emptyList()
        }
        return listOf(location.tokenLocator ?: utterance)
    }

    private fun textDecorationLocators(
        utterance: Locator,
        spans: List<SpeechMap.Span>,
    ): List<Locator> =
        spans.mapNotNull { span ->
            if (span.kind != SpeechMap.Kind.Text) return@mapNotNull null
            val quote = span.raw.trim()
            if (quote.isEmpty()) return@mapNotNull null
            val selector = span.selector.ifBlank { utterance.locations.cssSelector }
            val other = utterance.locations.otherLocations.toMutableMap()
            other.remove("isMath")
            other.remove("mathSelector")
            other.remove("mathId")
            other.remove("hasInlineMath")
            other.remove(SpeechMap.KEY)
            if (!selector.isNullOrBlank()) other["cssSelector"] = selector
            utterance.copy(
                locations = utterance.locations.copy(otherLocations = other),
                text = Locator.Text(
                    before = span.prefix.takeLast(32).ifBlank { null },
                    highlight = quote,
                    after = span.suffix.take(32).ifBlank { null },
                ),
            )
        }

    private fun playOf(playback: MediaNavigator.Playback): TtsPlay =
        when (playback.state) {
            is TtsNavigator.State.Ended,
            is TtsNavigator.State.Failure,
            -> TtsPlay.Stopped
            else -> if (playback.playWhenReady) TtsPlay.Playing else TtsPlay.Paused
        }

    /**
     * A word range may name the formula it lands on. A sentence-level range may
     * name a formula only when that utterance is exactly one formula.
     */
    private data class SpokenFormula(
        val formulaId: String?,
        val nodeIds: List<String>,
        val mathml: String,
    )

    private fun spokenFormula(location: TtsNavigator.Location?): SpokenFormula {
        if (location == null) return SpokenFormula(null, emptyList(), "")
        val utterance = location.utteranceLocator
        val href = utterance.href.toString()
        val map = SpeechMap.from(utterance)
        if (map != null) {
            val mathId = map.spokenMathId(location.range)
            val highlight = map.resolve(location.range)
            val mathml = map.spans.firstOrNull { span ->
                span.kind == SpeechMap.Kind.Math && span.mathId == mathId && span.mathml.isNotBlank()
            }?.mathml.orEmpty()
            val formulaId = mathId?.let { TtsSpeechState.key(bookId, href, it) }
            return SpokenFormula(formulaId, highlight.nodeIds, mathml)
        }
        if (!utterance.isFlag("isMath")) return SpokenFormula(null, emptyList(), "")
        val id = utterance.locations.otherLocations["mathId"] as? String
            ?: return SpokenFormula(null, emptyList(), "")
        return SpokenFormula(TtsSpeechState.key(bookId, href, id), emptyList(), "")
    }

    private fun utteranceIdOf(location: TtsNavigator.Location?): String {
        val utterance = location?.utterance ?: return ""
        return utterance.length.toString() + ":" + utterance.hashCode().toUInt().toString(16)
    }

    private fun Locator.isFlag(key: String): Boolean {
        val value = locations.otherLocations[key]
        return value == true || value == "true"
    }

    /**
     * Auto-follow uses the sentence or formula element. Spoken formula text is
     * not in the page, so it is removed before the navigator searches the DOM.
     */
    private fun followLocator(locator: Locator): Locator {
        if (!locator.isFlag("isMath") && !locator.isFlag("hasInlineMath")) {
            return locator
        }
        return locator.copy(text = Locator.Text())
    }

    fun start(navigator: Navigator) {
        (navigator as? VisualNavigator)?.let {
            attachNavigator(it)
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

    private fun VisualNavigator?.runJs(javascript: String, onResult: ((String?) -> Unit)? = null) {
        val nav = this as? EpubNavigatorFragment ?: return
        viewModelScope.launch {
            try {
                val result = nav.evaluateJavascript(javascript)
                onResult?.invoke(result)
            } catch (e: Exception) {
                Timber.w(e, "Unable to evaluate javascript on visualNavigator")
                onResult?.invoke(null)
            }
        }
    }

    fun stop() {
        launchJob = null
        clearFormulaHighlight()
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