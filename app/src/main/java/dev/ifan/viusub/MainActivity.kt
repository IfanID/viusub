package dev.ifan.viusub

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.ifan.viusub.databinding.ActivityMainBinding
import org.json.JSONObject

/**
 * Layar home: WebView penuh, dengan bar melayang di bawah.
 *
 * Bar dalam keadaan ringkas hanya menampilkan satu baris teks subtitle yang
 * sedang live. Saat diketuk, panel tombol (folder, reset, simpan JSON, simpan
 * SRT) muncul; diketuk lagi untuk menutup.
 *
 * Tidak ada split 30% lagi. WebView memakai seluruh layar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Mencegah render ulang untuk isi yang sama. */
    private var lastRenderedLine: String? = null
    private var lastRenderedStatus: String? = null

    /** Angka terakhir yang ditulis ke badge, supaya tidak digambar ulang. */
    private var lastCueNumber: Int = -1

    private var lastPayload: VtrPayload? = null
    private var folderUri: Uri? = null

    /**
     * Tampilan fullscreen native yang sedang aktif.
     *
     * Di Android WebView, `requestFullscreen()` hanya berhasil kalau aplikasi
     * mengimplementasikan `WebChromeClient.onShowCustomView`. Tanpa itu,
     * promise-nya ditolak dan halaman diam-diam tidak melakukan apa-apa —
     * persis gejala "tombol fullscreen ditekan tapi video tidak berubah".
     */
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var inFullscreen = false

    /** URL episode dari Home. Null kalau layar ini dibuka tanpa Intent. */
    private val startUrl: String by lazy {
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: START_URL
    }

    /** Judul episode dari Intent (dikirim Home). Null kalau dibuka langsung. */
    private val episodeTitle: String? by lazy { intent?.getStringExtra(EXTRA_TITLE) }
    private var polling = false
    private var expanded = false

    /**
     * Saat layar mati, video berhenti dan requestAnimationFrame berhenti,
     * jadi perekaman ikut berhenti. Tidak ada cara merekam tanpa layar
     * menyala, jadi opsi yang tersedia adalah menjaga layar tetap nyala.
     * Default aktif; bisa dimatikan lewat kotak centang di panel.
     */
    private var keepScreenOn = true

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling) return
            readPayload()
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!ExportStore.rememberTree(this, uri)) {
            say(getString(R.string.msg_folder_denied))
            return@registerForActivityResult
        }
        folderUri = uri
        say(getString(R.string.msg_folder_ok, uri.lastPathSegment ?: "?"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupWebView()
        setupBackHandling()

        // Preferensi harus dimuat SEBELUM setupLiveBar(), karena di sana
        // checkbox diisi nilainya. Kalau urutannya dibalik, checkbox akan
        // selalu menampilkan default dan mengabaikan pilihan yang tersimpan.
        folderUri = ExportStore.storedTree(this)
        keepScreenOn = getPreferences(MODE_PRIVATE)
            .getBoolean(KEY_KEEP_SCREEN, true)
        applyKeepScreenOn(keepScreenOn, save = false)

        setupLiveBar()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(startUrl)
        } else {
            binding.webView.restoreState(savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        polling = true
        mainHandler.post(pollTask)
    }

    override fun onPause() {
        polling = false
        mainHandler.removeCallbacks(pollTask)
        super.onPause()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            // IME harus dihitung terpisah. Dengan targetSdk 35+ jendela
            // digambar edge-to-edge, jadi adjustResize tidak mengecilkan
            // layout sendiri: kalau inset keyboard tidak dipakai sebagai
            // padding, kotak cari tertutup keyboard.
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            if (inFullscreen) {
                // Layar penuh: video mengisi semua ruang. Hanya batas takikan
                // (notch/camera cutout) yang tetap dihormati.
                val cutout = windowInsets.getInsets(
                    WindowInsetsCompat.Type.displayCutout(),
                )
                view.setPadding(
                    cutout.left,
                    cutout.top,
                    cutout.right,
                    maxOf(cutout.bottom, ime.bottom),
                )
            } else {
                val bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )
                // Bar melayang ikut digeser lewat padding root, jadi tidak
                // tertutup nav bar.
                view.setPadding(
                    bars.left,
                    bars.top,
                    bars.right,
                    maxOf(bars.bottom, ime.bottom),
                )
            }
            windowInsets
        }
    }

    // ---- fullscreen ----

    /**
     * Dipanggil saat halaman meminta video ditampilkan fullscreen.
     *
     * View yang diberikan framework biasanya SurfaceView atau TextureView
     * yang menggambar videonya. View itu dimasukkan ke [videoContainer],
     * parent yang sama dengan WebView. Karena badge dan bar melayang adalah
     * saudara yang ditulis setelahnya di layout, keduanya tetap di atas video.
     */
    private fun enterFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
    ) {
        if (customView != null) {
            // Already in fullscreen. Inform the page that the previous request
            // is void, then ignore the new one.
            callback.onCustomViewHidden()
            return
        }

        customView = view
        customCallback = callback
        inFullscreen = true

        binding.videoContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        applyFullscreenChrome(true)
    }

    /** Removes the fullscreen view and restores the normal screen chrome. */
    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        customCallback = null
        inFullscreen = false

        binding.videoContainer.removeView(view)
        applyFullscreenChrome(false)
    }

    /**
     * Adjusts the system chrome while in fullscreen.
     *
     * The system bar is hidden so the video really fills the screen, and the
     * floating bar is hidden because it sits at the bottom — exactly where the
     * player's own control bar is. The badge is intentionally kept: that is
     * the cue number indicator.
     */
    private fun applyFullscreenChrome(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            // Landscape follows suit; watching a fullscreen video in portrait
            // only leaves the video small. The manifest's configChanges keeps
            // the activity from being recreated, so the recording is unaffected.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        binding.liveBar.visibility = if (enabled) View.GONE else View.VISIBLE

        // The padding is recalculated from the new insets.
        ViewCompat.requestApplyInsets(binding.root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false

            // PENTING: tanpa ini situs melihat User-Agent WebView
            // ("... ; wv) ... Version/4.0 Chrome/...") dan sebagian besar
            // akan menolak diakses.
            userAgentString = ChromeUserAgent.from(userAgentString)
        }

        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installRecorder(view)
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

        // Tanpa WebChromeClient, WebView menolak permintaan fullscreen dari
        // halaman dan tidak menampilkan apa pun. Viu memakai requestFullscreen
        // untuk video fullscreen, jadi tanpa handler di sini tombol fullscreen
        // tidak pernah berefek.
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(
                view: View,
                callback: WebChromeClient.CustomViewCallback,
            ) {
                enterFullscreen(view, callback)
            }

            override fun onShowCustomView(
                view: View,
                requestedOrientation: Int,
                callback: WebChromeClient.CustomViewCallback,
            ) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Fullscreen duluan: video harus keluar dari layar penuh
                    // sebelum panel atau halaman yang ditangani.
                    if (inFullscreen) {
                        // Beri tahu halaman supaya player keluar dari
                        // fullscreen-nya juga, bukan cuma tampilan di app.
                        customCallback?.onCustomViewHidden()
                        exitFullscreen()
                        return
                    }
                    // Kalau panel sedang terbuka, back menutupnya dulu.
                    if (expanded) {
                        setExpanded(false)
                        return
                    }
                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun setupLiveBar() {
        binding.liveRow.setOnClickListener { setExpanded(!expanded) }

        binding.btnPickFolder.setOnClickListener {
            runCatching { pickFolder.launch(null) }
        }

        binding.chkKeepScreenOn.isChecked = keepScreenOn
        binding.chkKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            applyKeepScreenOn(checked, save = true)
        }

        binding.btnReset.setOnClickListener {
            binding.webView.evaluateJavascript(RecorderScript.RESET, null)
            lastRenderedLine = null
            lastPayload = null
            lastCueNumber = -1
            binding.cueBadge.visibility = View.GONE
            say(getString(R.string.panel_idle))
        }

        binding.btnSaveJson.setOnClickListener { exportJson() }
        binding.btnSaveSrt.setOnClickListener { exportSrt() }
    }

    private fun applyKeepScreenOn(enable: Boolean, save: Boolean) {
        keepScreenOn = enable
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (save) {
            getPreferences(MODE_PRIVATE).edit()
                .putBoolean(KEY_KEEP_SCREEN, enable)
                .apply()
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        binding.actionPanel.visibility = if (value) View.VISIBLE else View.GONE
        binding.chevron.text = if (value) CHEVRON_DOWN else CHEVRON_UP
    }

    /**
     * Memasang perekam ke halaman. Aman dipanggil berulang: di awal skrip ada
     * guard yang keluar kalau window.ViuSubRecorder sudah ada.
     */
    private fun installRecorder(view: WebView) {
        view.evaluateJavascript(RecorderScript.SOURCE, null)
        lastRenderedLine = null
    }

    private fun readPayload() {
        binding.webView.evaluateJavascript(RecorderScript.READ_PAYLOAD) { raw ->
            val json = raw?.let { unquote(it) }
            val payload = VtrPayload.parse(json)
            if (payload != null) render(payload)
        }
    }

    /**
     * evaluateJavascript mengembalikan hasil dalam bentuk JSON, jadi kalau JS
     * mengembalikan string, string itu ikut ter-escape. Bungkus dulu dalam
     * objek lalu ambil lagi supaya escape-nya dibongkar oleh parser.
     */
    private fun unquote(raw: String): String? = try {
        JSONObject("{\"v\":$raw}").optString("v").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    // ---- ekspor ----

    /**
     * Ekspor selalu membaca payload penuh dari halaman, bukan lastPayload.
     *
     * lastPayload berasal dari polling yang dipangkas ke 30 cue terakhir demi
     * hemat. Memakainya untuk ekspor akan menghasilkan file SRT yang hanya
     * berisi beberapa baris terakhir, tanpa ada peringatan.
     */
    private fun exportJson() {
        val tree = folderUri
        if (tree == null) {
            say(getString(R.string.msg_no_folder))
            return
        }
        withFullPayload { json, payload ->
            if (json == null || payload == null || payload.cueCount == 0) {
                say(getString(R.string.msg_nothing))
                return@withFullPayload
            }
            val name = SrtWriter.jsonFileName(payload, episodeTitle)
            val error = ExportStore.writeJson(this, tree, name, prettyPrint(json))
            say(error ?: getString(R.string.msg_saved, name))
        }
    }

    private fun exportSrt() {
        val tree = folderUri
        if (tree == null) {
            say(getString(R.string.msg_no_folder))
            return
        }
        withFullPayload { _, payload ->
            if (payload == null ||
                (payload.cues.isEmpty() && payload.pendingCues.isEmpty())
            ) {
                say(getString(R.string.msg_nothing))
                return@withFullPayload
            }
            val name = SrtWriter.fileName(payload, episodeTitle)
            val error = ExportStore.writeSrt(this, tree, name, SrtWriter.build(payload))
            say(error ?: getString(R.string.msg_saved, name))
        }
    }

    private fun withFullPayload(block: (String?, VtrPayload?) -> Unit) {
        binding.webView.evaluateJavascript(RecorderScript.READ_FULL) { raw ->
            val json = raw?.let { unquote(it) }
            block(json, VtrPayload.parse(json))
        }
    }

    /** Merapikan JSON satu field per baris supaya enak dibaca manusia. */
    private fun prettyPrint(json: String): String = try {
        JSONObject(json).toString(2)
    } catch (e: Exception) {
        json
    }

    // ---- tampilan ----

    private fun render(payload: VtrPayload) {
        lastPayload = payload

        updateCueBadge(payload.cueCount)

        val line = PayloadFormatter.liveLine(payload)
        if (line != lastRenderedLine) {
            lastRenderedLine = line
            binding.liveText.text = line.ifBlank { getString(R.string.panel_idle) }
        }

        // Status hanya perlu diperbarui saat panel terbuka.
        if (!expanded) return
        val status = PayloadFormatter.status(payload)
        if (status != lastRenderedStatus) {
            lastRenderedStatus = status
            binding.statusText.text = status
        }
    }

    /**
     * Badge pojok kiri atas: satu angka, jumlah cue yang sudah terekam.
     *
     * Anganya disembunyikan selama masih nol, supaya yang muncul hanya
     * angka yang benar-benar hasil rekaman — mulai dari 1.
     *
     * Cue count diambil dari payload penuh, bukan dari daftar yang
     * dipangkas 30 baris terakhir, jadi angkanya tidak pernah macet di
     * 30.
     */
    private fun updateCueBadge(jumlah: Int) {
        if (jumlah == lastCueNumber) return
        lastCueNumber = jumlah
        binding.cueBadge.text = jumlah.toString()
        binding.cueBadge.visibility = if (jumlah > 0) View.VISIBLE else View.GONE
    }

    /** Menampilkan pesan singkat di bar (mis. hasil ekspor). */
    private fun say(message: String) {
        binding.liveText.text = message
        lastRenderedLine = message
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val POLL_MS = 100L
        const val START_URL = "https://viu.com"
        const val KEY_KEEP_SCREEN = "keep_screen_on"
        const val EXTRA_TITLE = "episode_title"
        const val EXTRA_URL = "episode_url"

        // Ditulis sebagai escape supaya source tetap ASCII.
        const val CHEVRON_UP = "\u25B2"
        const val CHEVRON_DOWN = "\u25BC"
    }
}