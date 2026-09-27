/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann, Mostapha Idoubihi, Paul Stoica
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

@file:OptIn(InternalReadiumApi::class)

package org.readium.r2.navigator.pager

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.readium.r2.navigator.R
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.VoxScreenTiming
import org.readium.r2.navigator.R2WebView
import org.readium.r2.navigator.databinding.ReadiumNavigatorViewpagerFragmentEpubBinding
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubNavigatorViewModel
import org.readium.r2.navigator.extensions.htmlId
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

@OptIn(ExperimentalReadiumApi::class)
internal class R2EpubPageFragment : Fragment() {

    private val resourceUrl: AbsoluteUrl?
        get() = BundleCompat.getParcelable(requireArguments(), "url", AbsoluteUrl::class.java)

    internal val link: Link?
        get() = BundleCompat.getParcelable(requireArguments(), "link", Link::class.java)

    private var pendingLocator: Locator? = null

    private val positionCount: Long
        get() = requireArguments().getLong("positionCount")

    var webView: R2WebView? = null
        private set

    private lateinit var containerView: View
    private val viewModel: EpubNavigatorViewModel by viewModels(
        ownerProducer = { requireParentFragment() }
    )

    private var _binding: ReadiumNavigatorViewpagerFragmentEpubBinding? = null
    private val binding get() = _binding!!

    private var isLoading: Boolean = false
    private var firstScreenReady: Boolean = false
    private var paintReady: Boolean = false
    private var enhanceAnchor: String? = null

    internal fun setFontSize(fontSize: Double) {
        textZoom = (fontSize * 100).roundToInt()
    }

    private var textZoom: Int = 100
        set(value) {
            field = value
            webView?.settings?.textZoom = value
        }

    /**
     * Indicates whether the resource is fully loaded in the web view.
     */
    @InternalReadiumApi
    val isLoaded: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /**
     * Waits for the page to be loaded.
     */
    @InternalReadiumApi
    suspend fun awaitLoaded() {
        isLoaded.first { it }
    }

    private val navigator: EpubNavigatorFragment?
        get() = parentFragment as? EpubNavigatorFragment

    private val shouldApplyInsetsPadding: Boolean
        get() = navigator?.config?.shouldApplyInsetsPadding ?: true

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(textZoomBundleKey, textZoom)

        super.onSaveInstanceState(outState)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        savedInstanceState
            ?.getInt(textZoomBundleKey)
            ?.takeIf { it > 0 }
            ?.let { textZoom = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLocator = BundleCompat.getParcelable(
            requireArguments(),
            "initialLocator",
            Locator::class.java
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = ReadiumNavigatorViewpagerFragmentEpubBinding.inflate(inflater, container, false)
        containerView = binding.root

        val webView = binding.webView
        this.webView = webView

        webView.visibility = View.INVISIBLE
        navigator?.webViewListener?.let { listener ->
            webView.listener = listener

            link?.let { link ->
                // Setup custom Javascript interfaces.
                for ((name, obj) in listener.javascriptInterfacesForResource(link)) {
                    if (obj != null) {
                        webView.addJavascriptInterface(obj, name)
                    }
                }
            }
        }

        webView.disablePageTurnsWhileScrolling =
            navigator?.config?.disablePageTurnsWhileScrolling ?: false
        webView.settings.javaScriptEnabled = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.textZoom = textZoom
        webView.resourceUrl = resourceUrl
        webView.setPadding(0, 0, 0, 0)
        webView.addJavascriptInterface(webView, "Android")

        var endReached = false
        webView.setOnOverScrolledCallback(object : R2BasicWebView.OnOverScrolledCallback {
            override fun onOverScrolled(
                scrollX: Int,
                scrollY: Int,
                clampedX: Boolean,
                clampedY: Boolean,
            ) {
                activity ?: return
                val metrics = DisplayMetrics()

                val topDecile = webView.contentHeight - 1.15 * metrics.heightPixels
                val bottomDecile = (webView.contentHeight - metrics.heightPixels).toDouble()

                when (scrollY.toDouble()) {
                    in topDecile..bottomDecile -> {
                        if (!endReached) {
                            endReached = true
                            webView.listener?.onPageEnded(endReached)
                        }
                    }
                    else -> {
                        if (endReached) {
                            endReached = false
                            webView.listener?.onPageEnded(endReached)
                        }
                    }
                }
            }
        })

        webView.webViewClient = object : WebViewClientCompat() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                (webView as? R2BasicWebView)?.shouldOverrideUrlLoading(request) ?: false

            override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean {
                // Do something with the event here
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isCurrentResource) VoxScreenTiming.mark("pageFinished")

                onPageFinished()

                link?.let {
                    webView.listener?.onResourceLoaded(webView, it)
                }

                val pageView = webView
                val locator = pendingLocator
                val positionFirst = isCurrentResource && !opensAtStart(locator) && locator != null
                if (!positionFirst || pageView == null || locator == null) {
                    pageView?.onContentReady { onLoadPage() }
                    return
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    if (view == null) return@launch
                    enhanceAnchor = locator.locations.htmlId
                    VoxScreenTiming.note("position", "beforeShow=1")
                    loadLocator(
                        pageView,
                        requireNotNull(navigator).overflow.value.readingProgression,
                        locator
                    )
                    pendingLocator = null
                    pageView.onContentReady { onLoadPage() }
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                (webView as? R2BasicWebView)?.shouldInterceptRequest(view, request)
        }

        webView.isHapticFeedbackEnabled = false
        webView.isLongClickable = false
        webView.setOnLongClickListener {
            false
        }

        resourceUrl?.let {
            isLoading = true
            isLoaded.value = false
            VoxScreenTiming.mark("webView")
            webView.loadUrl(it.toString())
        }

        setupPadding()

        // Forward a tap event when the web view is not ready to propagate the taps. This allows
        // to toggle a navigation UI while a page is loading, for example.
        binding.root.setOnClickListenerWithPoint { _, point ->
            webView.listener?.onTap(point, targetElement = null)
        }

        return containerView
    }

    private var isPageFinished = false
    private val pendingPageFinished = mutableListOf<() -> Unit>()

    /**
     * Will run the given [action] when the content of the [WebView] is loaded.
     */
    fun whenPageFinished(action: () -> Unit) {
        if (isPageFinished) {
            action()
        } else {
            pendingPageFinished.add(action)
        }
    }

    private fun onPageFinished() {
        isPageFinished = true
        pendingPageFinished.forEach { it() }
        pendingPageFinished.clear()
    }

    /**
     * Will run the given [action] when the content of the [WebView] is fully laid out.
     */
    private fun WebView.onContentReady(action: () -> Unit) {
        awaitVisual(0, action)
    }

    private fun WebView.awaitVisual(id: Int, action: () -> Unit) {
        val posted = SystemClock.elapsedRealtime()
        val current = isCurrentResource
        if (current) VoxScreenTiming.note("visualPost", "id=$id")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
            WebViewCompat.postVisualStateCallback(this, id.toLong()) {
                if (current) {
                    val wait = SystemClock.elapsedRealtime() - posted
                    VoxScreenTiming.note("visual", "id=$id wait=${wait}ms")
                }
                action()
            }
        } else {
            evaluateJavascript("true") {
                postDelayed(500, action)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val lifecycleOwner = viewLifecycleOwner
        lifecycleOwner.lifecycleScope.launch {
            viewModel.isScrollEnabled
                .flowWithLifecycle(lifecycleOwner.lifecycle)
                .collectLatest { webView?.scrollModeFlow?.value = it }
        }
    }

    override fun onDestroyView() {
        webView?.listener = null
        _binding = null

        super.onDestroyView()
    }

    override fun onDetach() {
        super.onDetach()

        // Prevent the web view from leaking when its parent is detached.
        // See https://stackoverflow.com/a/19391512/1474476
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
    }

    private fun setupPadding() {
        updatePadding()

        // Update padding when the scroll mode changes
        viewLifecycleOwner.lifecycleScope.launch {
            webView?.scrollModeFlow?.collectLatest {
                updatePadding()
            }
        }

        if (shouldApplyInsetsPadding) {
            // Update padding when the window insets change, for example when the navigation and status
            // bars are toggled.
            ViewCompat.setOnApplyWindowInsetsListener(containerView) { _, insets ->
                updatePadding()
                insets
            }
        }
    }

    private fun updatePadding() {
        if (view == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val window = activity?.window ?: return@repeatOnLifecycle
                var top = 0
                var bottom = 0

                // Add additional padding to take into account the display cutout, if needed.
                if (
                    shouldApplyInsetsPadding &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
                    window.attributes.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                ) {
                    // Request the display cutout insets from the decor view because the ones given by
                    // setOnApplyWindowInsetsListener are not always correct for preloaded views.
                    window.decorView.rootWindowInsets?.displayCutout?.let { displayCutoutInsets ->
                        top += displayCutoutInsets.safeInsetTop
                        bottom += displayCutoutInsets.safeInsetBottom
                    }
                }

                if (!viewModel.isScrollEnabled.value) {
                    val margin =
                        resources.getDimension(R.dimen.readium_navigator_epub_vertical_padding)
                            .toInt()
                    top += margin
                    bottom += margin
                }

                containerView.setPadding(0, top, 0, bottom)
            }
        }
    }

    internal val paddingTop: Int get() = containerView.paddingTop
    internal val paddingBottom: Int get() = containerView.paddingBottom

    private val isCurrentResource: Boolean get() {
        val epubNavigator = navigator ?: return false
        val currentFragment = (epubNavigator.resourcePager.adapter as? R2PagerAdapter)?.getCurrentFragment() as? R2EpubPageFragment ?: return false
        return tag == currentFragment.tag
    }

    private fun onLoadPage() {
        if (!isLoading) return
        isLoading = false
        isLoaded.value = true

        if (view == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            var shown = false
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                val webView = requireNotNull(webView)
                webView.visibility = View.VISIBLE
                if (shown) return@repeatOnLifecycle
                shown = true
                firstScreenReady = true
                val opening = isCurrentResource
                val deferredLocator = if (opening) pendingLocator else null
                if (opening) pendingLocator = null
                if (opening) {
                    VoxScreenTiming.mark("visible")
                    VoxScreenTiming.note("firstScreen", "ready=1")
                    webView.awaitVisual(1) {
                        if (!isCurrentResource) return@awaitVisual
                        paintReady = true
                        VoxScreenTiming.mark("paint")
                        webView.evaluateJavascript(VISIBLE_TEXT_LENGTH) { raw ->
                            val chars = raw?.filter { it.isDigit() }.orEmpty().ifEmpty { "0" }
                            VoxScreenTiming.mark("text", " chars=$chars")
                        }
                        startFormulaEnhance()
                    }
                    deferredLocator?.let { locator ->
                        loadLocator(
                            webView,
                            requireNotNull(navigator).overflow.value.readingProgression,
                            locator
                        )
                    }
                } else {
                    // A preloaded chapter can be enhanced once the reader swipes to it.
                    paintReady = true
                    pendingLocator
                        ?.let { locator ->
                            loadLocator(
                                webView,
                                requireNotNull(navigator).overflow.value.readingProgression,
                                locator
                            )
                        }
                        .also { pendingLocator = null }
                }

                link?.let {
                    webView.listener?.onPageLoaded(webView, it)
                }
            }
        }
    }

    /**
     * Starts formula enhancement for a chapter the reader has already landed on.
     * The opening chapter waits until its first frame is committed.
     */
    internal fun onChapterSelected() {
        if (!firstScreenReady || !paintReady) return
        startFormulaEnhance()
    }

    private fun startFormulaEnhance() {
        val webView = webView ?: return
        val anchor = enhanceAnchor
            ?.replace("\\", "\\\\")
            ?.replace("\"", "\\\"")
        val argument = if (anchor == null) "null" else "\"$anchor\""
        webView.evaluateJavascript(
            "window.voxBeginEnhance && window.voxBeginEnhance($argument)",
            null
        )
    }

    private fun opensAtStart(locator: Locator?): Boolean {
        if (locator == null) return true
        val locations = locator.locations
        if (locations.htmlId != null) return false
        if (locator.text.highlight != null) return false
        if (locations["cssSelector"] != null || locations["mathSelector"] != null) return false
        val math = locations["isMath"]
        if (math == true || math == "true") return false
        val inline = locations["hasInlineMath"]
        if (inline == true || inline == "true") return false
        val progression = locations.progression ?: return true
        return progression <= 0.001
    }

    internal fun loadLocator(locator: Locator) {
        if (!isLoaded.value) {
            pendingLocator = locator
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                val webView = requireNotNull(webView)
                val epubNavigator = requireNotNull(navigator)
                loadLocator(webView, epubNavigator.overflow.value.readingProgression, locator)
                webView.listener?.onProgressionChanged()
            }
        }
    }

    private suspend fun loadLocator(
        webView: R2WebView,
        readingProgression: ReadingProgression,
        locator: Locator,
    ) {
        val followsFormula = locator.locations["isMath"] == true ||
            locator.locations["isMath"] == "true" ||
            locator.locations["hasInlineMath"] == true ||
            locator.locations["hasInlineMath"] == "true"
        val hasElementTarget = followsFormula ||
            locator.locations["cssSelector"] != null ||
            locator.locations["mathSelector"] != null

        if (locator.text.highlight != null || hasElementTarget) {
            if (webView.scrollToLocator(locator)) {
                return
            }
            if (followsFormula) {
                android.util.Log.w(
                    "VoxRead",
                    "Formula locator has no usable rect; falling back to progression for ${locator.href}"
                )
            }
        }

        val htmlId = locator.locations.htmlId
        if (htmlId != null && webView.scrollToId(htmlId)) {
            return
        }

        var progression = locator.locations.progression
        if (progression == null) {
            android.util.Log.w(
                "VoxRead",
                "Skipping locator; no usable rect and no progression for ${locator.href}"
            )
            return
        }

        // We need to reverse the progression with RTL because the Web View
        // always scrolls from left to right, no matter the reading direction.
        progression =
            if (webView.scrollMode || readingProgression == ReadingProgression.LTR) {
                progression
            } else {
                1 - progression
            }

        if (webView.scrollMode) {
            webView.scrollToPosition(progression)
        } else {
            // Figure out the target web view "page" from the requested
            // progression.
            var item = (progression * webView.numPages).roundToInt()
            if (readingProgression == ReadingProgression.RTL && item > 0) {
                item -= 1
            }
            webView.setCurrentItem(item, false)
        }
    }

    fun runJavaScript(script: String, callback: ((String) -> Unit)? = null) {
        whenPageFinished {
            requireNotNull(webView).runJavaScript(script, callback)
        }
    }

    suspend fun runJavaScriptSuspend(javascript: String): String = suspendCoroutine { cont ->
        runJavaScript(javascript) { result ->
            cont.resume(result)
        }
    }

    companion object {
        private const val textZoomBundleKey = "org.readium.textZoom"
        private const val VISIBLE_TEXT_LENGTH = """
            (function () {
              var body = document.body;
              if (!body) return 0;
              var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
              var count = 0;
              var node;
              while ((node = walker.nextNode())) {
                var parent = node.parentElement;
                if (!parent) continue;
                var rect = parent.getBoundingClientRect();
                if (rect.bottom <= 0 || rect.top >= window.innerHeight || rect.right <= 0 || rect.left >= window.innerWidth) continue;
                var text = (node.textContent || "").replace(/\s+/g, "");
                count += text.length;
                if (count > 2000) break;
              }
              return count;
            })()
        """

        fun newInstance(
            url: AbsoluteUrl,
            link: Link? = null,
            initialLocator: Locator? = null,
            positionCount: Int = 0,
        ): R2EpubPageFragment =
            R2EpubPageFragment().apply {
                arguments = Bundle().apply {
                    putParcelable("url", url)
                    putParcelable("link", link)
                    putParcelable("initialLocator", initialLocator)
                    putLong("positionCount", positionCount.toLong())
                }
            }
    }
}

/**
 * Same as setOnClickListener, but will also report the tap point in the view.
 */
private fun View.setOnClickListenerWithPoint(action: (View, PointF) -> Unit) {
    var point = PointF()

    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) {
            point = PointF(event.x, event.y)
        }
        false
    }

    setOnClickListener {
        action(it, point)
    }
}
