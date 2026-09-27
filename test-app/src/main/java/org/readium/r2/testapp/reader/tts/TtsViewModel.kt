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
import org.json.JSONObject
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

    private fun highlightColorScript(cssColor: String): String {
        val quoted = JSONObject.quote(cssColor)
        return "document.documentElement.style.setProperty('--vox-tts-highlight', $quoted);"
    }

    private fun formulaHighlightScript(locator: Locator): String {
        val other = locator.locations.otherLocations
        val payload = JSONObject()
            .put("inline", locator.isFlag("hasInlineMath"))
            .put("mathId", other["mathId"] as? String ?: JSONObject.NULL)
            .put("mathSelector", other["mathSelector"] as? String ?: JSONObject.NULL)
            .put("cssSelector", other["cssSelector"] as? String ?: JSONObject.NULL)
        return "window.voxReadHighlightMath && window.voxReadHighlightMath($payload);"
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

    val highlight: StateFlow<Locator?> =
        mediaServiceFacade.session.flatMapLatest { session ->
            session?.ttsNavigator?.location?.map { location ->
                val utterance = location.utteranceLocator
                if (utterance.isFlag("isMath") || utterance.isFlag("hasInlineMath")) {
                    null
                } else {
                    location.tokenLocator ?: utterance
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
                visualNavigator?.runJs(highlightColorScript(getCssHighlightColor(newColor)))
            }
            .launchIn(viewModelScope)

        mediaServiceFacade.session
            .flatMapLatest { it?.ttsNavigator?.location ?: MutableStateFlow(null) }
            .map { it?.utteranceLocator }
            .distinctUntilChanged()
            .onEach { utterance ->
                if (utterance != null && (utterance.isFlag("isMath") || utterance.isFlag("hasInlineMath"))) {
                    applyFormulaHighlight(utterance)
                } else {
                    clearFormulaHighlight()
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

    private fun clearFormulaHighlight() {
        visualNavigator?.runJs("window.voxReadClearHighlight && window.voxReadClearHighlight();")
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

    private fun VisualNavigator?.runJs(javascript: String) {
        val nav = this as? EpubNavigatorFragment ?: return
        viewModelScope.launch {
            try {
                nav.evaluateJavascript(javascript)
            } catch (e: Exception) {
                Timber.w(e, "Unable to evaluate javascript on visualNavigator")
            }
        }
    }

    private fun applyFormulaHighlight(locator: Locator) {
        val navigator = visualNavigator ?: return
        val cssColor = getCssHighlightColor(highlightColor.value)
        navigator.runJs(
            highlightColorScript(cssColor) + "\n" + formulaHighlightScript(locator)
        )
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