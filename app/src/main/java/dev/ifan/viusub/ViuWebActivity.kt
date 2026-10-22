package dev.ifan.viusub

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ifan.viusub.databinding.ActivityViuWebBinding

/**
 * Halaman Viu penuh untuk login.
 *
 * Activity ini memakai CookieManager WebView yang sama dengan MainActivity.
 * Setelah pengguna menutup halaman, cookie di-flush supaya sesi login terbawa
 * saat episode dibuka dari Home. Kredensial tidak pernah dibaca atau
 * disimpan oleh aplikasi; semuanya tetap di cookie jar WebView.
 */
class ViuWebActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViuWebBinding

    /**
     * Cookie awal dipakai hanya untuk memberi tahu Home apakah sesi berubah.
     * Kalau pengguna keluar tanpa login, hasilnya tetap CANCELED.
     */
    private var initialCookies: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViuWebBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupBackHandling()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.viuWebView, true)
        initialCookies = cookieManager.getCookie(START_URL)

        binding.viuWebView.setBackgroundColor(
            ContextCompat.getColor(this, R.color.panel_bottom),
        )
        setupWebView()

        binding.btnViuBack.setOnClickListener { goBackOrFinish() }
        binding.btnViuDone.setOnClickListener { finish() }

        binding.viuWebView.loadUrl(START_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.viuWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false

            // Sama dengan pemutar: buang penanda WebView supaya Viu tidak
            // menolak halaman login karena menganggapnya browser tertanam.
            userAgentString = ChromeUserAgent.from(userAgentString)
        }

        binding.viuWebView.setRendererPriorityPolicy(
            WebView.RENDERER_PRIORITY_IMPORTANT,
            true,
        )

        binding.viuWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.viuProgress.visibility = View.VISIBLE
                binding.viuProgress.progress = 0

                val host = Uri.parse(url).host
                if (!host.isNullOrBlank()) {
                    binding.viuHost.text = host.removePrefix("www.")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.viuProgress.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                binding.viuProgress.visibility = View.GONE
                Toast.makeText(
                    this@ViuWebActivity,
                    R.string.viu_load_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                val isWeb = url.scheme == "http" || url.scheme == "https"
                if (isWeb) return false

                // mailto:, tel:, whatsapp:, intent: ... -> serahkan ke app lain
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }

        binding.viuWebView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 100) {
                    binding.viuProgress.visibility = View.GONE
                } else {
                    binding.viuProgress.visibility = View.VISIBLE
                    binding.viuProgress.progress = newProgress
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                super.onReceivedTitle(view, title)
                val bersih = title?.trim().orEmpty()
                if (bersih.isNotEmpty() && !bersih.startsWith("http")) {
                    binding.viuTitle.text = bersih
                }
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goBackOrFinish()
                }
            },
        )
    }

    private fun goBackOrFinish() {
        if (binding.viuWebView.canGoBack()) {
            binding.viuWebView.goBack()
        } else {
            finish()
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        binding.viuWebView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.viuWebView.onResume()
    }

    override fun finish() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val current = cookieManager.getCookie(START_URL)
        val berubah = !current.isNullOrBlank() && current != initialCookies
        setResult(if (berubah) RESULT_OK else RESULT_CANCELED)
        super.finish()
    }

    override fun onDestroy() {
        binding.viuWebView.stopLoading()
        binding.viuWebView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val START_URL = "https://www.viu.com/ott/id"
    }
}
