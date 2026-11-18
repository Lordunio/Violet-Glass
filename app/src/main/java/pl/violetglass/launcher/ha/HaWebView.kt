package pl.violetglass.launcher.ha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.Message
import android.webkit.*
import java.io.ByteArrayInputStream

/** Own app-private WebView profile; no JS bridge, injected token, password field or SSL bypass. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor") // JS is required; this injected view is created only by AndroidView, never XML.
class HaWebView(context: Context, private val origins: Set<String>,
    private val loading: (Boolean) -> Unit, private val failure: (HaFailure) -> Unit,
    private val external: (String) -> Unit,
) : WebView(context) {
    var released = false
        private set
    var suspended = false
        private set
    var resumeUrl: String? = null
    private val popups = mutableSetOf<WebView>()
    init {
        // A wrap-content WebView can resolve CSS vh to zero on ColorOS even
        // when Compose measures it to the full panel. Keep its native viewport fixed.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        id = pl.violetglass.launcher.R.id.ha_webview
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true; setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if(!isUserGesture || released || suspended || popups.size >= 2) return false
                val popup = WebView(context)
                popup.settings.apply { allowFileAccess = false; allowContentAccess = false; javaScriptEnabled = false }
                popups += popup
                var routed = false
                fun route(value: String) {
                    if(value == "about:blank" || routed) return
                    routed = true
                    if(HaUrls.safeResource(value) && HaUrls.origin(value) in origins) this@HaWebView.open(value)
                    else resolveHaExternalTarget(value)?.let(external)
                    popup.stopLoading()
                    popup.post { if(popups.remove(popup)) popup.destroy() }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { route(request.url.toString()); return true }
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { route(url) }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val value = request.url.toString()
                if (value == "about:blank") return false
                if (!HaUrls.safeResource(value)) {
                    if(request.isForMainFrame) resolveHaExternalTarget(value)?.let(external)
                    return true
                }
                if (request.isForMainFrame && HaUrls.origin(value) !in origins) { external(value); return true }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val scheme = request.url.scheme
                return if ((scheme == "http" || scheme == "https") && !HaUrls.safeResource(request.url.toString()))
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != "about:blank" && !suspended) loading(true)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url != "about:blank" && !suspended) { loading(false); CookieManager.getInstance().flush() }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !suspended) { loading(false); failure(HaFailure.CONNECTION) }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400 && !suspended) { loading(false); failure(HaFailure.SERVER) }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel(); loading(false); failure(HaFailure.CERTIFICATE)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                release(); failure(HaFailure.RENDERER)
                return true
            }
        }
        isSaveEnabled = false // Never serialize browsing/auth state into Activity savedInstanceState.
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val edge = 48 * resources.displayMetrics.density
                // A generous right half starts page navigation; the left interior
                // stays available for horizontal dashboard controls and maps.
                parent?.requestDisallowInterceptTouchEvent(event.x > edge && event.x < width * .5f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(event)
    }
    fun open(value: String) {
        if (released) return
        resumeUrl = value; suspended = false; resumeTimers(); onResume(); loadUrl(value)
    }
    fun suspendPage() {
        if (released || suspended) return
        suspended = true
        stopLoading(); loadUrl("about:blank"); onPause()
        CookieManager.getInstance().flush()
    }
    fun resumePage() { if (suspended && !released) resumeUrl?.let(::open) }
    fun release() {
        if (released) return
        released = true; stopLoading(); onPause(); CookieManager.getInstance().flush()
        popups.toList().forEach { it.stopLoading(); it.destroy() }; popups.clear()
        removeAllViews(); destroy()
    }
}
enum class HaFailure { ADDRESS, CONNECTION, SERVER, CERTIFICATE, RENDERER }
