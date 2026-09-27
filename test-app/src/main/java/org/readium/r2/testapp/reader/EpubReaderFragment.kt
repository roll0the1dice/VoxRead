/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.SearchView
import androidx.core.os.BundleCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.html.toCss
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.epub.pageList
import org.readium.r2.testapp.LITERATA
import org.readium.r2.testapp.R
import org.readium.r2.testapp.reader.preferences.UserPreferencesViewModel
import org.readium.r2.testapp.reader.tts.TtsPlay
import org.readium.r2.testapp.reader.tts.TtsSpeechState
import org.readium.r2.testapp.search.SearchFragment

@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : VisualReaderFragment() {

    override lateinit var navigator: EpubNavigatorFragment

    private lateinit var menuSearch: MenuItem
    lateinit var menuSearchView: SearchView

    private var isSearchViewIconified = true

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            isSearchViewIconified = savedInstanceState.getBoolean(IS_SEARCH_VIEW_ICONIFIED)
        }

        val readerData = model.readerInitData as? EpubReaderInitData ?: run {
            // We provide a dummy fragment factory  if the ReaderActivity is restored after the
            // app process was killed because the ReaderRepository is empty. In that case, finish
            // the activity as soon as possible and go back to the previous one.
            childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            requireActivity().finish()
            return
        }

        childFragmentManager.fragmentFactory =
            readerData.navigatorFactory.createFragmentFactory(
                initialLocator = readerData.initialLocation,
                initialPreferences = readerData.preferencesManager.preferences.value,
                listener = model,
                configuration = EpubNavigatorFragment.Configuration {
                    // To customize the text selection menu.
                    selectionActionModeCallback = customSelectionActionModeCallback

                    // App assets which will be accessible from the EPUB resources.
                    // You can use simple glob patterns, such as "images/.*" to allow several
                    // assets in one go.
                    servedAssets = listOf(
                        // For the custom font Literata.
                        "fonts/.*",
                        // Icon for the annotation side mark, see [annotationMarkTemplate].
                        "annotation-icon.svg"
                    )

                    // Enable experimental decorations positioning that places highlights behind
                    // text to improve legibility with opaque decorations.
                    decorationTemplates = HtmlDecorationTemplates.defaultTemplates(
                        alpha = 1.0,
                        experimentalPositioning = true
                    )

                    // Register the HTML templates for our custom decoration styles.
                    decorationTemplates[DecorationStyleAnnotationMark::class] = annotationMarkTemplate()
                    decorationTemplates[DecorationStylePageNumber::class] = pageNumberTemplate()

                    registerJavascriptInterface("VoxFormula") {
                        VoxFormulaBridge(
                            opener = ::openFormulaViewer,
                            layoutReady = ::onFormulaLayoutReady,
                        )
                    }

                    // Declare a custom font family for reflowable EPUBs.
                    addFontFamilyDeclaration(FontFamily.LITERATA) {
                        addFontFace {
                            addSource("fonts/Literata-VariableFont_opsz,wght.ttf")
                            setFontStyle(FontStyle.NORMAL)
                            // Literata is a variable font family, so we can provide a font weight range.
                            setFontWeight(200..900)
                        }
                        addFontFace {
                            addSource("fonts/Literata-Italic-VariableFont_opsz,wght.ttf")
                            setFontStyle(FontStyle.ITALIC)
                            setFontWeight(200..900)
                        }
                    }
                }
            )

        childFragmentManager.setFragmentResultListener(
            SearchFragment::class.java.name,
            this,
            FragmentResultListener { _, result ->
                menuSearch.collapseActionView()
                BundleCompat.getParcelable(
                    result,
                    SearchFragment::class.java.name,
                    Locator::class.java
                )?.let {
                    navigator.go(it)
                }
            }
        )

        super.onCreate(savedInstanceState)
    }

    private var autoFormulaKey: String? = null
    private var dismissedFormulaKey: String? = null
    private var settledFormulaKey: String? = null
    private var pendingFormulaKey: String? = null
    private var awaitingFormulaLayout = false
    private var formulaQueryInFlight = false
    private var formulaQueryGen = 0
    private var recheckLongFormula = false
    private var queuedFormula: QueuedFormula? = null

    fun openFormulaViewer(payload: String) {
        val openedAt = SystemClock.elapsedRealtime()
        val json = try {
            JSONObject(payload)
        } catch (_: Exception) {
            logFormulaStage(openedAt, "open", " rejected=json")
            return
        }
        val mathml = json.optString("mathml")
        if (mathml.isBlank()) {
            logFormulaStage(openedAt, "open", " rejected=empty")
            return
        }
        val href = (navigator as? VisualNavigator)?.currentLocator?.value?.href?.toString().orEmpty()
        val key = model.scopedFormulaKey(href, json.optString("id"))
        val background = json.optString("background")
        val foreground = json.optString("color")
        val viewport = json.optViewport()
        val existing = childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG)
        if (existing != null) {
            if (autoFormulaKey == null || autoFormulaKey == key) return
            dismissedFormulaKey = autoFormulaKey
            dismissAutoFormula(
                QueuedFormula(key, mathml, automatic = false, background, foreground, viewport)
            )
            return
        }
        presentFormula(
            key,
            mathml,
            automatic = false,
            background = background,
            foreground = foreground,
            openedAt = openedAt,
            viewport = viewport,
        )
    }

    private fun onFormulaLayoutReady() {
        val speech = model.tts?.speech?.value ?: return
        val href = (navigator as? VisualNavigator)?.currentLocator?.value?.href?.toString() ?: return
        syncLongFormula(speech, href, fromLayout = true)
    }

    private fun syncLongFormula(
        state: TtsSpeechState,
        visibleHref: String,
        fromLayout: Boolean = false,
    ) {
        if (!isAdded) return
        val key = state.formulaId?.takeIf { it.isNotBlank() }
        if (dismissedFormulaKey != null && key != dismissedFormulaKey) dismissedFormulaKey = null

        val showing = childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG)
        val readingThis = key != null && state.play != TtsPlay.Stopped
        if (autoFormulaKey != null && autoFormulaKey == key && readingThis && showing != null) return

        if (autoFormulaKey != null && showing != null && (!readingThis || key != autoFormulaKey)) {
            dismissAutoFormula(null)
            if (key == null || !readingThis) return
        } else if (autoFormulaKey != null && showing == null) {
            autoFormulaKey = null
        }

        if (!readingThis || key == dismissedFormulaKey) {
            pendingFormulaKey = null
            awaitingFormulaLayout = false
            return
        }
        if (showing != null) return
        if (visibleHref != state.chapterHref) {
            pendingFormulaKey = key
            awaitingFormulaLayout = false
            settledFormulaKey = null
            return
        }
        if (!fromLayout && settledFormulaKey == key) return
        if (!fromLayout && pendingFormulaKey == key && (awaitingFormulaLayout || formulaQueryInFlight)) return
        requestLongFormula(key)
    }

    private fun requestLongFormula(key: String) {
        val localId = key.substringAfterLast('\u0000')
        if (localId.isBlank()) return
        val generation = ++formulaQueryGen
        pendingFormulaKey = key
        formulaQueryInFlight = true
        val quoted = JSONObject.quote(localId)
        viewLifecycleOwner.lifecycleScope.launch {
            val raw = try {
                navigator.evaluateJavascript(
                    "(window.voxReadLongFormula && window.voxReadLongFormula($quoted)) || \"\""
                )
            } catch (_: Exception) {
                null
            }
            if (generation != formulaQueryGen) return@launch
            formulaQueryInFlight = false
            val speech = model.tts?.speech?.value
            if (speech?.formulaId != key || speech.play == TtsPlay.Stopped) return@launch
            if (childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) != null) return@launch
            val report = parseJsJson(raw)
            when (report?.optString("status")) {
                "long" -> {
                    val mathml = report.optString("mathml")
                    pendingFormulaKey = null
                    awaitingFormulaLayout = false
                    settledFormulaKey = key
                    if (mathml.isNotBlank()) {
                        presentFormula(
                            key,
                            mathml,
                            automatic = true,
                            background = report.optString("background"),
                            foreground = report.optString("color"),
                            viewport = report.optViewport(),
                        )
                    }
                }
                "short" -> {
                    pendingFormulaKey = null
                    awaitingFormulaLayout = false
                    settledFormulaKey = key
                }
                else -> {
                    pendingFormulaKey = key
                    awaitingFormulaLayout = true
                }
            }
        }
    }

    private fun presentFormula(
        key: String,
        mathml: String,
        automatic: Boolean,
        background: String = "",
        foreground: String = "",
        openedAt: Long = SystemClock.elapsedRealtime(),
        viewport: FormulaViewport? = null,
    ) {
        if (!isAdded || childFragmentManager.isStateSaved) return
        if (childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) != null) return
        if (automatic) autoFormulaKey = key
        locatorBeforeFormula = (navigator as? VisualNavigator)?.currentLocator?.value
        formulaFollowSuspended = true
        logFormulaStage(openedAt, "open")
        viewLifecycleOwner.lifecycleScope.launch {
            var pageBackground = background
            var pageForeground = foreground
            if (pageBackground.isBlank() || pageForeground.isBlank()) {
                val colors = parseJsJson(
                    navigator.evaluateJavascript(
                        "(window.voxReadPageColors && window.voxReadPageColors()) || \"\""
                    )
                )
                if (pageBackground.isBlank()) pageBackground = colors?.optString("background").orEmpty()
                if (pageForeground.isBlank()) pageForeground = colors?.optString("color").orEmpty()
            }
            if (!isAdded || childFragmentManager.isStateSaved) return@launch
            if (childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) != null) return@launch
            if (automatic) {
                val speech = model.tts?.speech?.value
                if (speech?.formulaId != key || speech.play == TtsPlay.Stopped) {
                    if (autoFormulaKey == key) autoFormulaKey = null
                    formulaFollowSuspended = false
                    return@launch
                }
            }
            navigator.evaluateJavascript("window.__voxFormulaOpen = true;")
            if (!isAdded) {
                formulaFollowSuspended = false
                return@launch
            }
            if (
                childFragmentManager.isStateSaved ||
                childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) != null
            ) {
                if (childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) == null) {
                    releaseUnopenedFormula(automatic, key)
                }
                return@launch
            }
            copyThenShowFormula(
                key = key,
                mathml = mathml,
                automatic = automatic,
                background = parseCssColor(pageBackground) ?: Color.WHITE,
                foreground = parseCssColor(pageForeground) ?: Color.BLACK,
                openedAt = openedAt,
                viewport = viewport,
            )
        }
    }

    /**
     * The copy has to finish before the dialog covers the page. A hung copy
     * still opens the window, with the preparing state and a native close button.
     */
    private fun copyThenShowFormula(
        key: String,
        mathml: String,
        automatic: Boolean,
        background: Int,
        foreground: Int,
        openedAt: Long,
        viewport: FormulaViewport?,
    ) {
        val main = Handler(Looper.getMainLooper())
        var done = false
        lateinit var giveUp: Runnable
        fun deliver(bitmap: Bitmap?) {
            if (done) {
                if (bitmap != null) {
                    logFormulaStage(openedAt, "captureLate", " ${bitmap.width}x${bitmap.height}")
                    bitmap.recycle()
                }
                return
            }
            done = true
            main.removeCallbacks(giveUp)
            if (!isAdded) {
                bitmap?.recycle()
                formulaFollowSuspended = false
                return
            }
            val showing = childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) != null
            if (childFragmentManager.isStateSaved || showing) {
                bitmap?.recycle()
                if (!showing) releaseUnopenedFormula(automatic, key)
                return
            }
            if (automatic) {
                val speech = model.tts?.speech?.value
                if (speech?.formulaId != key || speech.play == TtsPlay.Stopped) {
                    bitmap?.recycle()
                    releaseUnopenedFormula(automatic, key)
                    return
                }
            }
            val copy = if (bitmap == null) " ok=0" else " ok=1 ${bitmap.width}x${bitmap.height}"
            logFormulaStage(openedAt, "capture", copy)
            suspendReaderChrome()
            stageFormulaPreview(bitmap)
            FormulaViewerDialog.newInstance(
                key = key,
                mathml = mathml,
                orientation = requireActivity().requestedOrientation,
                background = background,
                foreground = foreground,
                openedAt = openedAt,
            ).show(childFragmentManager, FormulaViewerDialog.TAG)
        }
        giveUp = Runnable { deliver(null) }
        val box = viewport
        if (box == null) {
            deliver(null)
            return
        }
        main.postDelayed(giveUp, 400)
        navigator.captureViewport(
            x = box.x,
            y = box.y,
            width = box.width,
            height = box.height,
            viewportWidth = box.viewportWidth,
            viewportHeight = box.viewportHeight,
            offsetX = box.offsetX,
            offsetY = box.offsetY,
        ) { bitmap ->
            deliver(bitmap)
        }
    }

    private fun releaseUnopenedFormula(automatic: Boolean, key: String) {
        if (automatic && autoFormulaKey == key) autoFormulaKey = null
        formulaFollowSuspended = false
        if (!isAdded || view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            navigator.evaluateJavascript("window.__voxFormulaOpen = false;")
        }
    }

    private fun dismissAutoFormula(next: QueuedFormula?) {
        queuedFormula = next
        recheckLongFormula = next == null
        autoFormulaKey = null
        pendingFormulaKey = null
        awaitingFormulaLayout = false
        formulaQueryInFlight = false
        formulaQueryGen++
        val dialog = childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) as? FormulaViewerDialog
        if (dialog != null) {
            dialog.dismissAllowingStateLoss()
        } else {
            val pending = queuedFormula
            queuedFormula = null
            recheckLongFormula = false
            pending?.let {
                presentFormula(
                    it.key,
                    it.mathml,
                    it.automatic,
                    it.background,
                    it.foreground,
                    viewport = it.viewport,
                )
            }
        }
    }

    override fun consumeFormulaViewerHandoff(): Boolean {
        val queued = queuedFormula
        queuedFormula = null
        val userClosed = autoFormulaKey != null
        if (userClosed) {
            dismissedFormulaKey = autoFormulaKey
            autoFormulaKey = null
        }
        if (queued != null) {
            recheckLongFormula = false
            afterViewerClosed {
                if (queued.automatic) {
                    val speech = model.tts?.speech?.value
                    if (speech?.formulaId != queued.key || speech.play == TtsPlay.Stopped) return@afterViewerClosed
                }
                presentFormula(
                    queued.key,
                    queued.mathml,
                    queued.automatic,
                    queued.background,
                    queued.foreground,
                    viewport = queued.viewport,
                )
            }
            return true
        }
        if (!userClosed && recheckLongFormula) {
            recheckLongFormula = false
            afterViewerClosed {
                val speech = model.tts?.speech?.value ?: return@afterViewerClosed
                val href = (navigator as? VisualNavigator)?.currentLocator?.value?.href?.toString()
                    ?: return@afterViewerClosed
                syncLongFormula(speech, href)
            }
        }
        return false
    }

    private fun afterViewerClosed(block: () -> Unit) {
        val host = view
        if (host == null) {
            block()
            return
        }
        host.post {
            if (!isAdded) return@post
            if (childFragmentManager.findFragmentByTag(FormulaViewerDialog.TAG) != null) {
                host.post { if (isAdded) block() }
                return@post
            }
            block()
        }
    }

    private fun parseJsJson(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                is String -> if (value.isBlank()) null else JSONObject(value)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class FormulaViewport(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val viewportWidth: Float,
        val viewportHeight: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    private fun JSONObject.optViewport(): FormulaViewport? {
        val box = optJSONObject("preview") ?: return null
        val width = box.optDouble("w", 0.0).toFloat()
        val height = box.optDouble("h", 0.0).toFloat()
        val viewportWidth = box.optDouble("vw", 0.0).toFloat()
        val viewportHeight = box.optDouble("vh", 0.0).toFloat()
        if (width < 2f || height < 2f || viewportWidth < 2f || viewportHeight < 2f) return null
        return FormulaViewport(
            x = box.optDouble("x").toFloat(),
            y = box.optDouble("y").toFloat(),
            width = width,
            height = height,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            offsetX = box.optDouble("ox").toFloat(),
            offsetY = box.optDouble("oy").toFloat(),
        )
    }

    private data class QueuedFormula(
        val key: String,
        val mathml: String,
        val automatic: Boolean,
        val background: String = "",
        val foreground: String = "",
        val viewport: FormulaViewport? = null,
    )

    private fun parseCssColor(value: String): Int? {
        val text = value.trim()
        if (text.startsWith("#")) {
            val hex = text.removePrefix("#")
            val expanded = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            val color = expanded.toLongOrNull(16) ?: return null
            return if (hex.length == 8) color.toInt() else (0xFF000000L or color).toInt()
        }
        val match = Regex(
            """rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([0-9.]+)\s*)?\)"""
        ).matchEntire(text) ?: return null
        val red = match.groupValues[1].toIntOrNull() ?: return null
        val green = match.groupValues[2].toIntOrNull() ?: return null
        val blue = match.groupValues[3].toIntOrNull() ?: return null
        val alpha = ((match.groupValues[4].toFloatOrNull() ?: 1f) * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, red, green, blue)
    }

    fun pauseFormulaSpeech() {
        model.tts?.pause()
    }

    fun resumeFormulaSpeech() {
        model.tts?.play()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)

        if (savedInstanceState == null) {
            childFragmentManager.commitNow {
                add(
                    R.id.fragment_reader_container,
                    EpubNavigatorFragment::class.java,
                    Bundle(),
                    NAVIGATOR_FRAGMENT_TAG
                )
            }
        }
        navigator = childFragmentManager.findFragmentByTag(NAVIGATOR_FRAGMENT_TAG) as EpubNavigatorFragment

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        @Suppress("Unchecked_cast")
        (model.settings as UserPreferencesViewModel<EpubSettings, EpubPreferences>)
            .bind(navigator, viewLifecycleOwner)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Display page number labels if the book contains a `page-list` navigation document.
                (navigator as? DecorableNavigator)?.applyPageNumberDecorations()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val speech = model.tts?.speech ?: return@repeatOnLifecycle
                val pages = (navigator as? VisualNavigator)?.currentLocator ?: return@repeatOnLifecycle
                combine(speech, pages) { state, locator ->
                    state to locator.href.toString()
                }.collect { (state, href) ->
                    syncLongFormula(state, href)
                }
            }
        }

        val menuHost: MenuHost = requireActivity()

        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuSearch = menu.findItem(R.id.search).apply {
                        isVisible = true
                        menuSearchView = actionView as SearchView
                    }

                    connectSearch()
                    if (!isSearchViewIconified) menuSearch.expandActionView()
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    when (menuItem.itemId) {
                        R.id.search -> {
                            return true
                        }
                        android.R.id.home -> {
                            menuSearch.collapseActionView()
                            return true
                        }
                    }
                    return false
                }
            },
            viewLifecycleOwner
        )
    }

    /**
     * Will display margin labels next to page numbers in an EPUB publication with a `page-list`
     * navigation document.
     *
     * See http://kb.daisy.org/publishing/docs/navigation/pagelist.html
     */
    private suspend fun DecorableNavigator.applyPageNumberDecorations() {
        val decorations = publication.pageList
            .mapIndexedNotNull { index, link ->
                val label = link.title ?: return@mapIndexedNotNull null
                val locator = publication.locatorFromLink(link) ?: return@mapIndexedNotNull null

                Decoration(
                    id = "page-$index",
                    locator = locator,
                    style = DecorationStylePageNumber(label = label)
                )
            }

        applyDecorations(decorations, "pageNumbers")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(IS_SEARCH_VIEW_ICONIFIED, isSearchViewIconified)
    }

    private fun connectSearch() {
        menuSearch.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {

            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (isSearchViewIconified) { // It is not a state restoration.
                    showSearchFragment()
                }

                isSearchViewIconified = false
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                isSearchViewIconified = true
                childFragmentManager.popBackStack()
                menuSearchView.clearFocus()

                return true
            }
        })

        menuSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {

            override fun onQueryTextSubmit(query: String): Boolean {
                model.search(query)
                menuSearchView.clearFocus()

                return false
            }

            override fun onQueryTextChange(s: String): Boolean {
                return false
            }
        })

        menuSearchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn).setOnClickListener {
            menuSearchView.requestFocus()
            model.cancelSearch()
            menuSearchView.setQuery("", false)

            (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(
                this.view,
                0
            )
        }
    }

    private fun showSearchFragment() {
        childFragmentManager.commit {
            childFragmentManager.findFragmentByTag(SEARCH_FRAGMENT_TAG)?.let { remove(it) }
            add(
                R.id.fragment_reader_container,
                SearchFragment::class.java,
                Bundle(),
                SEARCH_FRAGMENT_TAG
            )
            hide(navigator)
            addToBackStack(SEARCH_FRAGMENT_TAG)
        }
    }

    companion object {
        private const val SEARCH_FRAGMENT_TAG = "search"
        private const val NAVIGATOR_FRAGMENT_TAG = "navigator"
        private const val IS_SEARCH_VIEW_ICONIFIED = "isSearchViewIconified"
    }
}

// Examples of HTML templates for custom Decoration Styles.

/**
 * This Decorator Style will display a tinted "pen" icon in the page margin to show that a highlight
 * has an associated note.
 *
 * Note that the icon is served from the app assets folder.
 */
private fun annotationMarkTemplate(@ColorInt defaultTint: Int = Color.YELLOW): HtmlDecorationTemplate {
    val className = "testapp-annotation-mark"
    val iconUrl = checkNotNull(EpubNavigatorFragment.assetUrl("annotation-icon.svg"))
    return HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        width = HtmlDecorationTemplate.Width.PAGE,
        element = { decoration ->
            val style = decoration.style as? DecorationStyleAnnotationMark
            val tint = style?.tint ?: defaultTint
            // Using `data-activable=1` prevents the whole decoration container from being
            // clickable. Only the icon will respond to activation events.
            """
            <div><div data-activable="1" class="$className" style="background-color: ${tint.toCss()} !important"/></div>"
            """
        },
        stylesheet = """
            .$className {
                float: left;
                margin-left: 8px;
                width: 30px;
                height: 30px;
                border-radius: 50%;
                background: url('$iconUrl') no-repeat center;
                background-size: auto 50%;
                opacity: 0.8;
            }
            """
    )
}

/**
 * This Decoration Style is used to display the page number labels in the margins, when a book
 * provides a `page-list`. The label is stored in the [DecorationStylePageNumber] itself.
 *
 * See http://kb.daisy.org/publishing/docs/navigation/pagelist.html
 */
private fun pageNumberTemplate(): HtmlDecorationTemplate {
    val className = "testapp-page-number"
    return HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        width = HtmlDecorationTemplate.Width.PAGE,
        element = { decoration ->
            val style = decoration.style as? DecorationStylePageNumber

            // Using `var(--RS__backgroundColor)` is a trick to use the same background color as
            // the Readium theme. If we don't set it directly inline in the HTML, it might be
            // forced transparent by Readium CSS.
            """
            <div><span class="$className" style="background-color: var(--RS__backgroundColor) !important">${style?.label}</span></div>"
            """
        },
        stylesheet = """
            .$className {
                float: left;
                margin-left: 8px;
                padding: 0px 4px 0px 4px;
                border: 1px solid;
                border-radius: 20%;
                box-shadow: rgba(50, 50, 93, 0.25) 0px 2px 5px -1px, rgba(0, 0, 0, 0.3) 0px 1px 3px -1px;
                opacity: 0.8;
            }
            """
    )
}
