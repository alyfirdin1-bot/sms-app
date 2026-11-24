package com.captchapay.smsgateway

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PreviewLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private var serverUrl: String = ""
    private var verifying = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serverUrl = (intent.getStringExtra(EXTRA_SERVER_URL) ?: GatewayConfig.getServerUrl(this))
            .trim()
            .trimEnd('/')

        if (!serverUrl.startsWith("https://") && !serverUrl.startsWith("http://")) {
            Toast.makeText(this, "Invalid Server URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF020617.toInt())
        }

        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0F172A.toInt())
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        tvStatus = TextView(this).apply {
            text = "Sign in with Google below to unlock Preview URL"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val btnVerifyDone = Button(this).apply {
            text = "Verify & Save"
            textSize = 11f
            setOnClickListener { verifyCookieAndFinish(manual = true) }
        }

        val btnClose = Button(this).apply {
            text = "Close"
            textSize = 11f
            setOnClickListener { finish() }
        }

        topBar.addView(tvStatus)
        topBar.addView(btnVerifyDone)
        topBar.addView(btnClose)

        webView = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(topBar)
        root.addView(webView)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = CHROME_MOBILE_UA
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                val currentUrl = url.orEmpty()
                val targetHost = Uri.parse(serverUrl).host.orEmpty()
                if (
                    targetHost.isNotEmpty() &&
                    currentUrl.contains(targetHost) &&
                    !currentUrl.contains("__cookie_check") &&
                    !currentUrl.contains("accounts.google.com")
                ) {
                    verifyCookieAndFinish(manual = false)
                }
            }
        }

        webView.loadUrl(serverUrl)
    }

    private fun verifyCookieAndFinish(manual: Boolean) {
        if (verifying) return
        verifying = true

        CookieManager.getInstance().flush()
        val hostUrl = try {
            val u = Uri.parse(serverUrl)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            serverUrl
        }
        val cookieForUrl = CookieManager.getInstance().getCookie(serverUrl).orEmpty()
        val cookieForHost = CookieManager.getInstance().getCookie(hostUrl).orEmpty()
        val bestCookie = if (cookieForUrl.length >= cookieForHost.length) cookieForUrl else cookieForHost

        if (bestCookie.isBlank()) {
            verifying = false
            if (manual) {
                Toast.makeText(
                    this,
                    "Please finish Google sign-in on the page first.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        GatewayConfig.setPreviewCookie(this, bestCookie)
        tvStatus.text = "Testing direct server connection..."

        GatewayApiClient.sendHeartbeatAsync(this) { res ->
            runOnUiThread {
                verifying = false
                if (res.ok) {
                    Toast.makeText(
                        this,
                        "Preview Session Connected ✓ Direct sync active!",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_OK)
                    finish()
                } else if (manual) {
                    tvStatus.text = "Wait for page to load (${res.code}), then tap Verify & Save"
                    Toast.makeText(
                        this,
                        "Cookie saved, server returned ${res.code}: ${res.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
