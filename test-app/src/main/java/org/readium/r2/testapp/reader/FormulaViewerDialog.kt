package org.readium.r2.testapp.reader

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.testapp.reader.tts.TtsSpeechState

/**
 * Full-screen formula viewer. The copy lives in its own WebView, so it does not
 * reuse the book DOM id and page swipes never reach the reader.
 */
class FormulaViewerDialog : DialogFragment() {

    private var webView: WebView? = null
    private var pageReady = false
    private var viewerInstance: Int = 0
    private var latest = TtsSpeechState.stopped(0)
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        web.settings.allowFileAccessFromFileURLs = true
        web.settings.builtInZoomControls = false
        web.settings.displayZoomControls = false
        web.setBackgroundColor(VIEWER_BACKGROUND)
        web.addJavascriptInterface(ViewerHost(), "VoxHost")
        web.webViewClient = WebViewClient()
        web.loadDataWithBaseURL(
            "file:///android_asset/readium/readium-css/",
            viewerHtml(
                mathml = requireArguments().getString(ARG_MATH).orEmpty(),
                openedKey = requireArguments().getString(ARG_KEY).orEmpty(),
                viewerId = viewerInstance,
            ),
            "text/html",
            "utf-8",
            null,
        )
        webView = web
        return FrameLayout(context).apply {
            setBackgroundColor(VIEWER_BACKGROUND)
            addView(
                web,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val speech = (parentFragment as? EpubReaderFragment)?.model?.tts?.speech
                    ?: return@repeatOnLifecycle
                speech.collect { state ->
                    latest = state
                    if (pageReady) push(state)
                }
            }
        }
    }

    private fun push(state: TtsSpeechState) {
        val web = webView ?: return
        val payload = JSONObject()
            .put("viewer", viewerInstance)
            .put("session", state.session)
            .put("seq", state.sequence)
            .put("play", state.play.name.lowercase())
            .put("formula", state.formulaId ?: "")
        web.evaluateJavascript("window.voxApplySpeech && window.voxApplySpeech($payload);", null)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawable(ColorDrawable(VIEWER_BACKGROUND))
        }
    }

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }

    private var finished = false

    private fun closeViewer() {
        dismissAllowingStateLoss()
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
        fun ready() {
            handler.post {
                pageReady = true
                val current = (parentFragment as? EpubReaderFragment)?.model?.tts?.speech?.value
                    ?: latest
                latest = current
                push(current)
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
        const val VIEWER_BACKGROUND = 0xFF1C1C1C.toInt()
        private const val ARG_KEY = "vox-formula-key"
        private const val ARG_MATH = "vox-formula-math"
        private const val ARG_ORIENTATION = "vox-formula-orientation"
        private val nextViewer = AtomicInteger(0)

        fun newInstance(key: String, mathml: String, orientation: Int) =
            FormulaViewerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KEY, key)
                    putString(ARG_MATH, mathml)
                    putInt(ARG_ORIENTATION, orientation)
                }
            }
    }
}

class VoxFormulaBridge(
    private val opener: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun open(payload: String) {
        handler.post { opener(payload) }
    }
}

private fun viewerHtml(mathml: String, openedKey: String, viewerId: Int): String {
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
          html, body { margin: 0; height: 100%; background: #1c1c1c; color: #f5f5f5; overflow: hidden; }
          #stage {
            position: absolute; left: 0; right: 0; top: 36px; bottom: 72px;
            display: flex; align-items: center; justify-content: center;
            overflow: hidden; touch-action: none;
          }
          #formula { display: inline-block; transform-origin: center center; padding: 16px; }
          #formula math, #formula * {
            font-family: "AcademicMath", "STIX Two Math", serif;
            color: #f5f5f5;
          }
          #formula.speaking { background: rgba(255, 220, 40, 0.35); }
          #formula.paused-here { box-shadow: inset 0 0 0 2px #ff9800; }
          #bar, #banner {
            position: absolute; left: 0; right: 0;
            display: flex; flex-wrap: wrap; gap: 8px;
            justify-content: center; align-items: center;
            padding: 8px; box-sizing: border-box;
            background: rgba(0,0,0,0.82);
          }
          #bar { bottom: 0; }
          #banner { top: 36px; display: none; }
          #banner.is-open { display: flex; }
          button {
            border: 0; border-radius: 8px; padding: 8px 12px;
            background: #3a3a3a; color: #f5f5f5; font-size: 15px;
          }
        </style>
        </head>
        <body>
          <div id="stage"><div id="formula">__VOX_MATH__</div></div>
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
            var appliedSession = -1, appliedSeq = -1, sawSession = false;
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
            }
            function readable() {
              pinch = 1; panX = 0; panY = 0;
              fontScale = 1;
              mode = "read";
              apply();
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
            window.voxApplySpeech = function (payload) {
              if (!payload || payload.viewer !== viewerId) return;
              if (payload.session === appliedSession && payload.seq <= appliedSeq) return;
              if (payload.session !== appliedSession) appliedSeq = -1;
              if (payload.seq <= appliedSeq) return;
              appliedSession = payload.session;
              appliedSeq = payload.seq;
              if (payload.session > 0) sawSession = true;
              var match = !!payload.formula && payload.formula === opened;
              formula.classList.toggle("speaking", match && payload.play === "playing");
              formula.classList.toggle("paused-here", match && payload.play === "paused");
              banner.className = "";
              if (match && payload.play === "playing") showStatus("正在朗读", "暂停", "pause");
              else if (match && payload.play === "paused") showStatus("已暂停", "继续", "resume");
              else if (payload.play === "stopped" && sawSession) showStatus("朗读已停止", "", "");
            };
            window.addEventListener("resize", function () { if (mode === "fit") fit(); });
            banner.className = "";
            function reveal() { fit(); }
            if (document.fonts && document.fonts.ready) document.fonts.ready.then(reveal);
            requestAnimationFrame(reveal);
            if (window.VoxHost && VoxHost.ready) VoxHost.ready();
          </script>
        </body>
        </html>
    """.trimIndent()
        .replace("__VOX_MATH__", mathml)
        .replace("__VOX_OPENED__", JSONObject.quote(openedKey))
        .replace("__VOX_VIEWER__", viewerId.toString())
}
