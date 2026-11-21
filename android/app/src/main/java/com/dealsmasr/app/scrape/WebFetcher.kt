package com.dealsmasr.app.scrape

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Loads a page in an invisible WebView (the phone's real Chrome engine), waits for JavaScript to render
 * the products, scrolls to trigger lazy loading, and returns the final HTML.
 * Used for sites that render with JavaScript (noon, talabat) or reject plain HTTP clients (Jumia).
 */
class WebFetcher(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    suspend fun render(url: String, settleMs: Long = 3500, timeoutMs: Long = 35_000): String? =
        withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.Main) { load(url, settleMs) } }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String, settleMs: Long): String? = suspendCancellableCoroutine { cont ->
        val wv = WebView(context.applicationContext)
        CookieManager.getInstance().setAcceptCookie(true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadsImagesAutomatically = false
        wv.settings.blockNetworkImage = true
        var done = false
        var scheduled = false

        fun finish(html: String?) {
            if (done) return
            done = true
            main.post { runCatching { wv.stopLoading(); wv.destroy() } }
            if (cont.isActive) cont.resume(html)
        }

        fun grab(round: Int) {
            wv.evaluateJavascript(
                "(function(){window.scrollTo(0,document.body.scrollHeight);return document.documentElement.outerHTML;})()"
            ) { json ->
                val html = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull()
                // Pages that are still rendering get one more short wait.
                if (round == 0 && (html == null || html.length < 20_000)) main.postDelayed({ grab(1) }, settleMs)
                else finish(html)
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (scheduled || done) return
                scheduled = true
                // scroll once early so lazy lists start loading, then read the DOM
                main.postDelayed({ if (!done) view.evaluateJavascript("window.scrollTo(0,document.body.scrollHeight/2)", null) }, settleMs / 2)
                main.postDelayed({ if (!done) grab(0) }, settleMs)
            }
        }
        cont.invokeOnCancellation { main.post { if (!done) { done = true; runCatching { wv.destroy() } } } }
        wv.loadUrl(url)
    }
}
