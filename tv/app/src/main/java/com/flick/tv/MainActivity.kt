package com.flick.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView

private const val PORT = 8000

/**
 * The whole receiver: an HTTP/WebSocket server, an mDNS advertisement, and a
 * full-screen WebView showing the persistent kiosk page. All three live and
 * die with this activity, so the TV only receives casts while the app is open.
 */
class MainActivity : Activity() {
    private var server: ReceiverServer? = null
    private var advertiser: Advertiser? = null
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // A timeout of 0 matters: NanoWSD's WebSocket read loop treats the
        // default 5 s socket timeout as a dead connection, which would drop
        // the kiosk page's socket every time it sat idle.
        server = ReceiverServer(applicationContext, PORT).also { it.start(0, false) }
        advertiser = Advertiser(this).also { it.start(PORT, "Flick TV") }

        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Embedded players (YouTube) refuse to start without a gesture
            // otherwise, and nobody is touching a TV screen.
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl("http://127.0.0.1:$PORT/")
        }
        webView = view
        setContentView(view)

        requestOverlayPermission()
    }

    // Without "Display over other apps", Android silently blocks this app
    // from opening the YouTube app or coming back to the front while another
    // app is showing, so only the first cast after launch would ever appear.
    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        } catch (e: ActivityNotFoundException) {
            // A TV without that screen can still receive casts while this
            // app is open, which is all it could ever do without it.
        }
    }

    override fun onDestroy() {
        advertiser?.stop()
        server?.stop()
        webView?.destroy()
        super.onDestroy()
    }
}
