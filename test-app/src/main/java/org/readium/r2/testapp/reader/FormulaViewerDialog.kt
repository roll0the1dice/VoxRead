package org.readium.r2.testapp.reader

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import java.util.concurrent.atomic.AtomicInteger
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.testapp.reader.tts.TtsHighlightColor
import org.readium.r2.testapp.reader.tts.TtsPlay
import org.readium.r2.testapp.reader.tts.TtsSpeechState

/**
 * Full-screen formula viewer. The copy lives in its own WebView, so it does not
 * reuse the book DOM id and page swipes never reach the reader.
 *
 * The first frame is a native preview and close button. The WebView is created
 * after that frame, and it stays covered until the formula has a real size,
 * the first fit has run, and [WebView.postVisualStateCallback] reports a frame.
 */
class FormulaViewerDialog : DialogFragment() {

    private var webView: WebView? = null
    private var cover: View? = null
    private var previewImage: ImageView? = null
    private var previewBitmap: Bitmap? = null
    private var pageReady = false
    private var paintRequested = false
    private var paintRequest = 0L
    private var viewerAttached = false
    private var destroyed = false
    private var viewerInstance: Int = 0
    private var trackedSpeech = TtsSpeechState.stopped(0)
    private var trackedColor = TtsHighlightColor.DEFAULT
    private var trackedRevision = 0L
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val openedAt: Long
        get() = arguments?.getLong(ARG_OPENED) ?: 0L

    private val attachViewer = Runnable {
        val root = view as? ViewGroup ?: return@Runnable
        if (destroyed || !isAdded || viewerAttached) return@Runnable
        viewerAttached = true
        createViewer(root)
    }

    private val paintTimeout = Runnable {
        if (isAdded && !pageReady) logFormulaStage(openedAt, "paintTimeout")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_DeviceDefault_NoActionBar)
        isCancelable = true
        viewerInstance = nextViewer.incrementAndGet()
        savedOrientation = requireArguments().getInt(
            ARG_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            setDimAmount(0f)
            setBackgroundDrawable(
                ColorDrawable(requireArguments().getInt(ARG_BG, android.graphics.Color.WHITE))
            )
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val background = requireArguments().getInt(ARG_BG, android.graphics.Color.WHITE)
        val foreground = requireArguments().getInt(ARG_FG, android.graphics.Color.BLACK)
        val preview = takeFormulaPreview()
        val root = FrameLayout(context)
        root.setBackgroundColor(background)
        root.addView(buildCover(background, foreground, preview), matchParent())
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentPaint()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val tts = (parentFragment as? EpubReaderFragment)?.model?.tts
                    ?: return@repeatOnLifecycle
                val opened = requireArguments().getString(ARG_KEY).orEmpty()
                combine(tts.speech, tts.highlightColor) { state, color ->
                    state to color
                }.collect { (state, color) ->
                    val speakingThis = opened.isNotBlank() &&
                        state.formulaId == opened &&
                        state.play != TtsPlay.Stopped
                    if (speakingThis) followedSpeech = true
                    if (followedSpeech && !speakingThis) {
                        closeAfterReading()
                        return@collect
                    }
                    val paint = rememberPaint(state, color)
                    if (pageReady) push(paint)
                }
            }
        }
        // Posting from the first pre-draw leaves this frame for the preview.
        // view.post from onCreateView would run before that draw.
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val observer = view.viewTreeObserver
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (!destroyed && !viewerAttached) {
                    logFormulaStage(openedAt, "window")
                    view.post(attachViewer)
                    view.postDelayed(paintTimeout, 8_000)
                }
                return true
            }
        })
    }

    private fun buildCover(background: Int, foreground: Int, preview: Bitmap?): View {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val cover = FrameLayout(requireContext())
        cover.setBackgroundColor(background)
        cover.isClickable = true
        cover.translationZ = 8f * density
        val column = LinearLayout(requireContext())
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        val holder = FrameLayout(requireContext())
        if (preview != null) {
            val image = ImageView(requireContext())
            image.setImageBitmap(preview)
            image.adjustViewBounds = true
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.maxWidth = resources.displayMetrics.widthPixels - pad * 2
            image.maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
            image.contentDescription = "公式预览"
            holder.addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
            )
            previewImage = image
            previewBitmap = preview
        }
        column.addView(
            holder,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )
        val status = TextView(requireContext())
        status.text = "正在准备公式"
        status.setTextColor(foreground)
        status.gravity = Gravity.CENTER
        status.textSize = 16f
        val close = Button(requireContext())
        close.text = "关闭"
        close.setOnClickListener { closeViewer() }
        val bar = LinearLayout(requireContext())
        bar.orientation = LinearLayout.VERTICAL
        bar.gravity = Gravity.CENTER_HORIZONTAL
        bar.addView(status)
        bar.addView(
            close,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (12 * density).toInt() }
        )
        column.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (28 * density).toInt()
                leftMargin = pad
                rightMargin = pad
            }
        )
        cover.addView(column, matchParent())
        this.cover = cover
        return cover
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createViewer(root: ViewGroup) {
        val context = requireContext()
        val background = requireArguments().getInt(ARG_BG, android.graphics.Color.WHITE)
        val foreground = requireArguments().getInt(ARG_FG, android.graphics.Color.BLACK)
        val ctorStarted = SystemClock.elapsedRealtime()
        val web = WebView(context)
        val ctorDone = SystemClock.elapsedRealtime()
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        web.settings.allowFileAccessFromFileURLs = true
        web.settings.builtInZoomControls = false
        web.settings.displayZoomControls = false
        web.setBackgroundColor(background)
        web.addJavascriptInterface(ViewerHost(), "VoxHost")
        web.webViewClient = WebViewClient()
        root.addView(web, 0, matchParent())
        webView = web
        if (isResumed) web.onResume()
        val loadStarted = SystemClock.elapsedRealtime()
        web.loadDataWithBaseURL(
            "file:///android_asset/readium/readium-css/",
            viewerHtml(
                mathml = requireArguments().getString(ARG_MATH).orEmpty(),
                openedKey = requireArguments().getString(ARG_KEY).orEmpty(),
                viewerId = viewerInstance,
                background = background,
                foreground = foreground,
            ),
            "text/html",
            "utf-8",
            null,
        )
        val loadDone = SystemClock.elapsedRealtime()
        logFormulaStage(
            openedAt,
            "webView",
            " ctor=${ctorDone - ctorStarted}ms load=${loadDone - loadStarted}ms",
        )
    }

    private data class SpeechPaint(
        val speech: TtsSpeechState,
        val color: TtsHighlightColor,
        val revision: Long,
    )

    private fun tts() = (parentFragment as? EpubReaderFragment)?.model?.tts

    private fun rememberPaint(state: TtsSpeechState, color: TtsHighlightColor): SpeechPaint {
        if (state != trackedSpeech || color != trackedColor) {
            trackedSpeech = state
            trackedColor = color
            trackedRevision += 1
        }
        return SpeechPaint(state, color, trackedRevision)
    }

    /** Latest speech and color. Only a real change gets a new [SpeechPaint.revision]. */
    private fun currentPaint(): SpeechPaint {
        val engine = tts()
        return rememberPaint(
            engine?.speech?.value ?: trackedSpeech,
            engine?.highlightColor?.value ?: trackedColor,
        )
    }

    private fun speechPayload(paint: SpeechPaint): JSONObject =
        JSONObject()
            .put("viewer", viewerInstance)
            .put("session", paint.speech.session)
            .put("seq", paint.speech.sequence)
            .put("revision", paint.revision)
            .put("play", paint.speech.play.name.lowercase())
            .put("formula", paint.speech.formulaId ?: "")
            .put("utterance", paint.speech.utteranceId)
            .put("nodes", org.json.JSONArray(paint.speech.activeNodeIds))
            .put("fill", paint.color.playingFill())
            .put("fillDim", paint.color.pausedFill())
            .put("edge", paint.color.speakEdge())

    private fun push(paint: SpeechPaint) {
        val web = webView ?: return
        val generation = viewerInstance
        web.evaluateJavascript(
            "window.voxApplySpeech && window.voxApplySpeech(${speechPayload(paint)});",
        ) {
            if (destroyed || generation != viewerInstance) return@evaluateJavascript
        }
    }

    private fun onFormulaFitted() {
        if (destroyed || paintRequested || !isAdded) return
        paintRequested = true
        logFormulaStage(openedAt, "fitted")
        paintSpeech(currentPaint(), allowRefresh = true)
    }

    /**
     * Applies the newest speech snapshot, waits until that apply is accepted,
     * then waits for a drawn frame before the preview comes off.
     * A newer snapshot that arrives while drawing is applied once.
     */
    private fun paintSpeech(paint: SpeechPaint, allowRefresh: Boolean) {
        val web = webView ?: return
        if (destroyed) return
        val generation = viewerInstance
        web.evaluateJavascript(
            "window.voxApplySpeech && window.voxApplySpeech(${speechPayload(paint)});",
        ) { raw ->
            if (destroyed || !isAdded || pageReady || generation != viewerInstance) return@evaluateJavascript
            val status = raw.jsToken()
            val accepted = status == "applied" || status == "duplicate"
            val newer = currentPaint()
            if (!accepted || (allowRefresh && newer.revision != paint.revision)) {
                if (newer.revision != paint.revision) {
                    paintSpeech(newer, allowRefresh = false)
                }
                return@evaluateJavascript
            }
            schedulePaint(paint, allowRefresh && status != "duplicate")
            webView?.postDelayed({
                if (!pageReady && !destroyed && generation == viewerInstance) {
                    schedulePaint(currentPaint(), allowRefresh = false)
                }
            }, 500)
        }
    }

    private fun schedulePaint(paint: SpeechPaint, allowRefresh: Boolean) {
        val target = webView ?: return
        if (destroyed || pageReady) return
        val generation = viewerInstance
        val request = ++paintRequest
        target.postVisualStateCallback(request, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (requestId != paintRequest || pageReady || destroyed || generation != viewerInstance) return
                val newer = currentPaint()
                if (allowRefresh && newer.revision != paint.revision) {
                    paintSpeech(newer, allowRefresh = false)
                    return
                }
                revealInteractive(paint)
            }
        })
    }

    private fun revealInteractive(painted: SpeechPaint) {
        if (pageReady || destroyed || !isAdded) return
        val newer = currentPaint()
        if (newer.revision != painted.revision) {
            paintSpeech(newer, allowRefresh = false)
            return
        }
        pageReady = true
        previewImage?.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        cover?.visibility = View.GONE
        view?.removeCallbacks(paintTimeout)
        logFormulaStage(openedAt, "paint", " revision=${painted.revision}")
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setDimAmount(0f)
            setBackgroundDrawable(ColorDrawable(requireArguments().getInt(ARG_BG, android.graphics.Color.WHITE)))
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (paintRequested && !pageReady && !destroyed) schedulePaint(currentPaint(), allowRefresh = false)
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        destroyed = true
        view?.removeCallbacks(attachViewer)
        view?.removeCallbacks(paintTimeout)
        val web = webView
        webView = null
        (web?.parent as? ViewGroup)?.removeView(web)
        web?.destroy()
        previewImage?.setImageDrawable(null)
        previewImage = null
        previewBitmap?.recycle()
        previewBitmap = null
        cover = null
        super.onDestroyView()
    }

    private var finished = false
    private var followedSpeech = false
    private var closedBySpeech = false

    private fun closeViewer() {
        dismissAllowingStateLoss()
    }

    /**
     * The window was showing the formula being read. Reading has moved on or
     * stopped, so the automatic view should leave. A formula opened by hand
     * and never spoken stays until the reader closes it.
     */
    private fun closeAfterReading() {
        if (finished || closedBySpeech) return
        closedBySpeech = true
        (parentFragment as? EpubReaderFragment)?.noteAutoFormulaSpeechEnded()
        closeViewer()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!finished) {
            finished = true
            activity?.requestedOrientation = savedOrientation
            (parentFragment as? EpubReaderFragment)?.onFormulaViewerClosed()
        }
        super.onDismiss(dialog)
    }

    private inner class ViewerHost {
        private val handler = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun close() {
            handler.post { closeViewer() }
        }

        @JavascriptInterface
        fun landscape() {
            handler.post {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        @JavascriptInterface
        fun inserted(width: Int, height: Int) {
            handler.post {
                if (!destroyed) logFormulaStage(openedAt, "inserted", " w=$width h=$height")
            }
        }

        @JavascriptInterface
        fun fitted() {
            handler.post { onFormulaFitted() }
        }

        @JavascriptInterface
        fun fontReady() {
            handler.post {
                if (!destroyed) logFormulaStage(openedAt, "font")
            }
        }

        @JavascriptInterface
        fun pause() {
            handler.post {
                (parentFragment as? EpubReaderFragment)?.pauseFormulaSpeech()
            }
        }

        @JavascriptInterface
        fun resume() {
            handler.post {
                (parentFragment as? EpubReaderFragment)?.resumeFormulaSpeech()
            }
        }
    }

    companion object {
        const val TAG = "vox-formula-viewer"
        private const val ARG_KEY = "vox-formula-key"
        private const val ARG_MATH = "vox-formula-math"
        private const val ARG_ORIENTATION = "vox-formula-orientation"
        private const val ARG_BG = "vox-formula-bg"
        private const val ARG_FG = "vox-formula-fg"
        private const val ARG_OPENED = "vox-formula-opened"
        private val nextViewer = AtomicInteger(0)

        fun newInstance(
            key: String,
            mathml: String,
            orientation: Int,
            background: Int,
            foreground: Int,
            openedAt: Long,
        ) =
            FormulaViewerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY, key)
                    putString(ARG_MATH, mathml)
                    putInt(ARG_ORIENTATION, orientation)
                    putInt(ARG_BG, background)
                    putInt(ARG_FG, foreground)
                    putLong(ARG_OPENED, openedAt)
                }
            }
    }
}

private fun matchParent() = FrameLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.MATCH_PARENT,
)

private var stagedPreview: Bitmap? = null

internal fun stageFormulaPreview(bitmap: Bitmap?) {
    val previous = stagedPreview
    stagedPreview = bitmap
    if (previous != null && previous !== bitmap) previous.recycle()
}

internal fun takeFormulaPreview(): Bitmap? = stagedPreview.also { stagedPreview = null }

internal fun logFormulaStage(openedAt: Long, stage: String, extra: String = "") {
    val elapsed = SystemClock.elapsedRealtime() - openedAt
    Log.i("VoxFormulaTiming", "stage=$stage t=${elapsed}ms$extra")
}

class VoxFormulaBridge(
    private val opener: (String) -> Unit,
    private val layoutReady: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun open(payload: String) {
        val openedAt = SystemClock.elapsedRealtime()
        handler.post {
            logFormulaStage(openedAt, "click")
            opener(payload)
        }
    }

    @JavascriptInterface
    fun layoutReady() {
        handler.post { layoutReady() }
    }
}

private fun viewerHtml(
    mathml: String,
    openedKey: String,
    viewerId: Int,
    background: Int,
    foreground: Int,
): String {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
        <style>
          @font-face {
            font-family: "AcademicMath";
            src: url("fonts/STIXTwoMath-Regular.woff2") format("woff2");
            font-display: swap;
          }
          html, body { margin: 0; height: 100%; background: __VOX_BG__; color: __VOX_FG__; overflow: hidden; }
          #stage {
            position: absolute; left: 0; right: 0; top: 36px; bottom: 72px;
            display: flex; align-items: center; justify-content: center;
            overflow: hidden; touch-action: none;
          }
          #formula {
            position: relative;
            display: inline-block; transform-origin: center center; padding: 16px;
            background: transparent; box-shadow: none;
          }
          #formula math, #formula math * {
            font-family: "AcademicMath", "STIX Two Math", serif;
            color: __VOX_FG__;
            background: transparent;
          }
          #marks {
            position: absolute; left: 0; top: 0; right: 0; bottom: 0;
            pointer-events: none; overflow: visible;
          }
          #marks .mark {
            position: absolute; border-radius: 6px;
            border: 0; box-shadow: none;
          }
          #bar, #banner {
            position: absolute; left: 0; right: 0;
            display: flex; flex-wrap: wrap; gap: 8px;
            justify-content: center; align-items: center;
            padding: 8px; box-sizing: border-box;
            background: transparent;
          }
          #bar { bottom: 0; }
          #banner { top: 36px; display: none; }
          #banner.is-open { display: flex; }
          button {
            border: 0; border-radius: 8px; padding: 8px 12px;
            background: __VOX_BUTTON__; color: __VOX_FG__; font-size: 15px;
          }
        </style>
        </head>
        <body>
          <div id="stage"><div id="formula"><div id="sheet">__VOX_MATH__</div><div id="marks"></div></div></div>
          <div id="banner">
            <span id="status"></span>
            <button id="pause" type="button"></button>
          </div>
          <div id="bar">
            <button id="fit" type="button">适应屏幕</button>
            <button id="raw" type="button">原始大小</button>
            <button id="land" type="button">横屏查看</button>
            <button id="close" type="button">关闭</button>
          </div>
          <script>
            var formula = document.getElementById("formula");
            var stage = document.getElementById("stage");
            var banner = document.getElementById("banner");
            var fontScale = 1, panX = 0, panY = 0, pinch = 1;
            var mode = "fit";
            var opened = __VOX_OPENED__;
            var viewerId = __VOX_VIEWER__;
            var appliedSession = -1, appliedRevision = -1;
            var savedSpeech = null, matchedOnce = false;
            var startX = 0, startY = 0, lastX = 0, lastY = 0, lastDist = 0, moved = false, lastTap = 0;

            function apply() {
              formula.style.fontSize = fontScale + "em";
              formula.style.transform = "translate(" + panX + "px," + panY + "px) scale(" + pinch + ")";
            }
            function fit() {
              pinch = 1; panX = 0; panY = 0;
              formula.style.fontSize = "1em";
              formula.style.transform = "none";
              var w = Math.max(formula.scrollWidth, formula.offsetWidth, 1);
              var h = Math.max(formula.scrollHeight, formula.offsetHeight, 1);
              var sx = Math.max(stage.clientWidth - 32, 1) / w;
              var sy = Math.max(stage.clientHeight - 32, 1) / h;
              fontScale = Math.max(0.35, Math.min(sx, sy, 1.6));
              mode = "fit";
              apply();
              if (window.voxRedrawSpeech) voxRedrawSpeech();
            }
            function readable() {
              pinch = 1; panX = 0; panY = 0;
              fontScale = 1;
              mode = "read";
              apply();
              if (window.voxRedrawSpeech) voxRedrawSpeech();
            }
            function dist(touches) {
              var dx = touches[0].clientX - touches[1].clientX;
              var dy = touches[0].clientY - touches[1].clientY;
              return Math.sqrt(dx * dx + dy * dy) || 1;
            }
            stage.addEventListener("touchstart", function (event) {
              moved = false;
              if (event.touches.length === 1) {
                startX = lastX = event.touches[0].clientX;
                startY = lastY = event.touches[0].clientY;
              } else if (event.touches.length === 2) {
                lastDist = dist(event.touches);
              }
              event.preventDefault();
            }, { passive: false });
            stage.addEventListener("touchmove", function (event) {
              if (event.touches.length === 1) {
                var x = event.touches[0].clientX;
                var y = event.touches[0].clientY;
                if (Math.abs(x - startX) + Math.abs(y - startY) > 8) moved = true;
                panX += x - lastX;
                panY += y - lastY;
                lastX = x;
                lastY = y;
                apply();
              } else if (event.touches.length === 2) {
                moved = true;
                var next = dist(event.touches);
                pinch = Math.min(4, Math.max(0.4, pinch * (next / lastDist)));
                lastDist = next;
                apply();
              }
              event.preventDefault();
            }, { passive: false });
            stage.addEventListener("touchend", function (event) {
              if (!moved && event.touches.length === 0) {
                var now = Date.now();
                if (now - lastTap < 280) {
                  if (mode === "fit") readable(); else fit();
                  lastTap = 0;
                } else {
                  lastTap = now;
                }
              }
            });
            document.getElementById("fit").addEventListener("click", fit);
            document.getElementById("raw").addEventListener("click", readable);
            document.getElementById("land").addEventListener("click", function () {
              if (window.VoxHost) VoxHost.landscape();
            });
            document.getElementById("close").addEventListener("click", function () {
              if (window.VoxHost) VoxHost.close();
            });
            var pauseButton = document.getElementById("pause");
            var status = document.getElementById("status");
            pauseButton.addEventListener("click", function () {
              if (!window.VoxHost) return;
              if (pauseButton.getAttribute("data-action") === "resume") VoxHost.resume();
              else VoxHost.pause();
            });
            function showStatus(text, button, action) {
              banner.className = "is-open";
              status.textContent = text;
              if (!button) {
                pauseButton.style.display = "none";
                return;
              }
              pauseButton.style.display = "";
              pauseButton.textContent = button;
              pauseButton.setAttribute("data-action", action);
            }
            function placeMarks(ids, fill) {
              var layer = document.getElementById("marks");
              var requested = ids ? ids.length : 0;
              var report = { requested: requested, found: 0, visible: 0, drawn: 0 };
              if (!layer) return report;
              layer.innerHTML = "";
              if (!requested) return report;
              var origin = layer.getBoundingClientRect();
              var sx = layer.clientWidth > 0 ? origin.width / layer.clientWidth : 0;
              var sy = layer.clientHeight > 0 ? origin.height / layer.clientHeight : 0;
              if (!(sx > 0) || !(sy > 0)) return report;
              for (var i = 0; i < ids.length; i++) {
                var node = document.getElementById(ids[i]);
                if (!node) continue;
                report.found++;
                var rect = node.getBoundingClientRect();
                if (rect.width < 0.5 || rect.height < 0.5) continue;
                report.visible++;
                var em = parseFloat(window.getComputedStyle(node).fontSize) || 16;
                var padX = em * 0.32;
                var padY = em * 0.22;
                var left = (rect.left - origin.left) / sx - padX;
                var top = (rect.top - origin.top) / sy - padY;
                var width = rect.width / sx + padX * 2;
                var height = rect.height / sy + padY * 2;
                var box = document.createElement("div");
                box.className = "mark";
                box.style.left = left + "px";
                box.style.top = top + "px";
                box.style.width = width + "px";
                box.style.height = height + "px";
                box.style.background = fill || "transparent";
                layer.appendChild(box);
                var placed = box.getBoundingClientRect();
                var expectLeft = origin.left + left * sx;
                var expectTop = origin.top + top * sy;
                var aligned = Math.abs(placed.left - expectLeft) <= 1 &&
                  Math.abs(placed.top - expectTop) <= 1 &&
                  Math.abs(placed.width - width * sx) <= 1 &&
                  Math.abs(placed.height - height * sy) <= 1;
                if (!aligned) {
                  box.remove();
                  continue;
                }
                report.drawn++;
              }
              return report;
            }
            function paintSpeech() {
              var payload = savedSpeech;
              if (!payload) return false;
              var match = !!payload.formula && payload.formula === opened;
              var playing = match && payload.play === "playing";
              var paused = match && payload.play === "paused";
              if (payload.fill) formula.style.setProperty("--vox-speak-fill", payload.fill);
              if (payload.fillDim) formula.style.setProperty("--vox-speak-fill-dim", payload.fillDim);
              if (payload.edge) formula.style.setProperty("--vox-speak-edge", payload.edge);
              formula.classList.toggle("speaking", playing);
              formula.classList.toggle("paused-here", paused);
              var marks = placeMarks(match && (playing || paused) ? payload.nodes : [], paused ? payload.fillDim : payload.fill);
              window.voxMarkReport = marks;
              banner.className = "";
              if (playing) {
                matchedOnce = true;
                showStatus("正在朗读", "暂停", "pause");
              } else if (paused) {
                matchedOnce = true;
                showStatus("已暂停", "继续", "resume");
              } else if (matchedOnce && payload.play === "stopped") {
                showStatus("朗读已停止", "", "");
              }
              var classesOk = playing === formula.classList.contains("speaking") &&
                paused === formula.classList.contains("paused-here");
              var marksOk = marks.requested === 0 ||
                (marks.found === marks.requested && marks.drawn === marks.visible);
              return classesOk && marksOk;
            }
            function acceptSpeech(payload, force) {
              if (!payload || payload.viewer !== viewerId) return "rejected";
              var session = typeof payload.session === "number" ? payload.session : 0;
              var revision = typeof payload.revision === "number" ? payload.revision : 0;
              if (!force && revision < appliedRevision) return "rejected";
              if (!force && revision === appliedRevision) {
                return session === appliedSession ? "duplicate" : "rejected";
              }
              appliedSession = session;
              appliedRevision = revision;
              savedSpeech = payload;
              return paintSpeech() ? "applied" : "rejected";
            }
            window.voxApplySpeech = function (payload) {
              return acceptSpeech(payload, false);
            };
            window.voxRedrawSpeech = function () {
              if (!savedSpeech) return false;
              return paintSpeech();
            };
            window.addEventListener("resize", function () {
              if (mode === "fit") fit();
              else if (window.voxRedrawSpeech) voxRedrawSpeech();
            });
            banner.className = "";
            var reportedSize = false;
            var reportedFit = false;
            var fitAttempts = 0;
            function formulaSize() {
              return {
                w: Math.max(formula.scrollWidth, formula.offsetWidth, 0),
                h: Math.max(formula.scrollHeight, formula.offsetHeight, 0)
              };
            }
            function stageReady() {
              return stage.clientWidth > 32 && stage.clientHeight > 32;
            }
            function prepareFormula() {
              if (reportedFit) return;
              var size = formulaSize();
              if (size.w >= 2 && size.h >= 2 && !reportedSize) {
                reportedSize = true;
                if (window.VoxHost && VoxHost.inserted) {
                  VoxHost.inserted(Math.round(size.w), Math.round(size.h));
                }
              }
              if (size.w < 2 || size.h < 2 || !stageReady()) {
                if (fitAttempts++ < 180) requestAnimationFrame(prepareFormula);
                return;
              }
              fit();
              requestAnimationFrame(function () {
                if (reportedFit) return;
                var fittedSize = formulaSize();
                if (fittedSize.w < 2 || fittedSize.h < 2 || !stageReady()) {
                  if (fitAttempts++ < 180) requestAnimationFrame(prepareFormula);
                  return;
                }
                reportedFit = true;
                if (window.VoxHost && VoxHost.fitted) VoxHost.fitted();
              });
            }
            if (document.fonts && document.fonts.ready) {
              document.fonts.ready.then(function () {
                if (window.VoxHost && VoxHost.fontReady) VoxHost.fontReady();
                if (mode === "fit") fit();
                else if (window.voxRedrawSpeech) voxRedrawSpeech();
              });
            }
            requestAnimationFrame(prepareFormula);
          </script>
        </body>
        </html>
    """.trimIndent()
        .replace("__VOX_BG__", background.cssRgb())
        .replace("__VOX_FG__", foreground.cssRgb())
        .replace("__VOX_BUTTON__", foreground.cssAlpha(0.08f))
        .replace("__VOX_MATH__", mathml)
        .replace("__VOX_OPENED__", JSONObject.quote(openedKey))
        .replace("__VOX_VIEWER__", viewerId.toString())
}

private fun Int.cssRgb(): String {
    val red = (this shr 16) and 0xFF
    val green = (this shr 8) and 0xFF
    val blue = this and 0xFF
    return "rgb($red, $green, $blue)"
}

private fun String?.jsToken(): String =
    this?.trim()?.trim('"').orEmpty()

private fun Int.cssAlpha(alpha: Float): String {
    val red = (this shr 16) and 0xFF
    val green = (this shr 8) and 0xFF
    val blue = this and 0xFF
    return "rgba($red, $green, $blue, $alpha)"
}
