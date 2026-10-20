package dev.ifan.viusub

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    /** Ekspor yang menunggu folder dipilih; dilanjutkan otomatis setelahnya. */
    private enum class ExportKind { JSON, SRT }
    private var pendingExport: ExportKind? = null

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
    private val episodeCover: String? by lazy { intent?.getStringExtra(EXTRA_COVER) }
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
            if (!splashDismissed && readyTick++ % 3 == 0) checkPlayerReady()
            if (scriptTick++ % 20 == 0) reinstallPageScripts()
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    /** Penanda layar sapaan sudah meluruh; poll kesiapan tidak perlu lagi. */
    private var splashDismissed = false
    private var readyTick = 0
    private var scriptTick = 0
    private var splashDots = 0
    private var splashPulse: ValueAnimator? = null

    /** Pengganti titik status "Menyiapkan…": berputar sampai pemain siap. */
    private val splashDotsTask = object : Runnable {
        override fun run() {
            if (splashDismissed) return
            splashDots++
            binding.splashStatus.text = getString(R.string.splash_waiting) +
                " " + "•".repeat((splashDots % 3) + 1)
            mainHandler.postDelayed(this, 500)
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val next = pendingExport
        pendingExport = null
        if (uri == null) {
            if (next != null) announce(getString(R.string.msg_save_cancelled), error = true)
            return@registerForActivityResult
        }
        if (!ExportStore.rememberTree(this, uri)) {
            say(getString(R.string.msg_folder_denied))
            announce(getString(R.string.msg_folder_denied), error = true)
            return@registerForActivityResult
        }
        folderUri = uri
        say(getString(R.string.msg_folder_ok, uri.lastPathSegment ?: "?"))
        // Ekspor yang tadi terhenti karena folder belum dipilih langsung
        // dilanjutkan, supaya pengguna tidak perlu menekan tombol simpan
        // sekali lagi.
        when (next) {
            ExportKind.JSON -> exportJson()
            ExportKind.SRT -> exportSrt()
            null -> {}
        }
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
            startSplash()
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
     * player's own control bar is. The cue number badge appears only while
     * fullscreen, so the normal view stays clean.
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

        // Angka cue hanya tampil selama fullscreen.
        refreshCueBadge()

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

        // Renderer tidak boleh turun prioritas hanya karena WebView
        // tertutup layar sapaan: dokumen yang dianggap tersembunyi bisa
        // di-throttle Chromium, dan loop 250 ms skrip fokus bisa mati.
        binding.webView.setRendererPriorityPolicy(
            WebView.RENDERER_PRIORITY_IMPORTANT,
            true,
        )

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Skrip dipasang sedini mungkin supaya dokumen mana pun —
                // termasuk hasil redirect di tengah-tengah muatan — langsung
                // dipandu sejak awal; guard di kedua skrip membuat pemasangan
                // ganda tidak berbahaya.
                installPageScripts(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                installPageScripts(view)
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
     * Memasang skrip ke halaman: perekam subtitle dan mode "hanya pemain".
     * Aman dipanggil berulang: keduanya punya guard di awal skrip yang
     * keluar kalau penandanya sudah ada.
     */
    private fun installPageScripts(view: WebView) {
        view.evaluateJavascript(RecorderScript.SOURCE, null)
        view.evaluateJavascript(PlayerFocusScript.SOURCE, null)
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
            pendingExport = ExportKind.JSON
            say(getString(R.string.msg_no_folder))
            announce(getString(R.string.msg_pick_folder_first), error = true)
            runCatching { pickFolder.launch(null) }
            return
        }
        announce(getString(R.string.msg_saving_json))
        withFullPayload { json, payload ->
            if (json == null || payload == null || payload.cueCount == 0) {
                say(getString(R.string.msg_nothing))
                announce(getString(R.string.msg_nothing), error = true)
                return@withFullPayload
            }
            val name = SrtWriter.jsonFileName(payload, episodeTitle)
            val error = ExportStore.writeJson(this, tree, name, prettyPrint(json))
            if (error == null) {
                say(getString(R.string.msg_saved, name))
                announce(getString(R.string.msg_saved, name))
            } else {
                say(error)
                announce(error, error = true)
            }
        }
    }

    private fun exportSrt() {
        val tree = folderUri
        if (tree == null) {
            pendingExport = ExportKind.SRT
            say(getString(R.string.msg_no_folder))
            announce(getString(R.string.msg_pick_folder_first), error = true)
            runCatching { pickFolder.launch(null) }
            return
        }
        announce(getString(R.string.msg_saving_srt))
        withFullPayload { _, payload ->
            if (payload == null ||
                (payload.cues.isEmpty() && payload.pendingCues.isEmpty())
            ) {
                say(getString(R.string.msg_nothing))
                announce(getString(R.string.msg_nothing), error = true)
                return@withFullPayload
            }
            val name = SrtWriter.fileName(payload, episodeTitle)
            val error = ExportStore.writeSrt(this, tree, name, SrtWriter.build(payload))
            if (error == null) {
                say(getString(R.string.msg_saved, name))
                announce(getString(R.string.msg_saved, name))
            } else {
                say(error)
                announce(error, error = true)
            }
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

    // ---- layar sapaan ----

    /**
     * Layar sapaan dipasang sebelum halaman dimuat: thumbnail episode,
     * judul, dan status yang titiknya berputar. Semuanya hidup sampai
     * <video> di halaman benar-benar siap, lalu layarnya meluruh dan
     * memperlihatkan pemain yang sudah dalam tampilan benar.
     *
     * Dilewati kalau layar ini dibuka tanpa URL episode (tanpa Home).
     */
    private fun startSplash() {
        if (episodeTitle.isNullOrBlank() && episodeCover.isNullOrBlank()) return

        binding.splashOverlay.alpha = 1f
        binding.splashOverlay.visibility = View.VISIBLE

        binding.splashTitle.text = episodeTitle.orEmpty()
        binding.splashTitle.visibility =
            if (episodeTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        episodeCover?.takeIf { it.isNotBlank() }?.let {
            ThumbnailLoader.load(binding.splashCover, it)
        }

        binding.splashStatus.text = getString(R.string.splash_waiting)
        binding.splashContent.translationY = 34f
        binding.splashContent.alpha = 0f
        binding.splashContent.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(360)
            .setStartDelay(60)
            .start()

        splashPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                binding.splashCover.alpha = 0.62f + 0.38f * f
                binding.splashCover.scaleX = 0.97f + 0.03f * f
                binding.splashCover.scaleY = 0.97f + 0.03f * f
            }
            start()
        }
        mainHandler.post(splashDotsTask)
        mainHandler.postDelayed({ dismissSplash() }, SPLASH_TIMEOUT_MS)
    }

    /**
     * Cek kesiapan lewat EvaluateJavascript. Hanya "2" (zona pemain sudah
     * dipromosikan, tampilan bentuk akhir) yang meluruhkan layar sapaan.
     * Tidak meluruh sekadar karena halaman bersih: menunggu di layar
     * sapaan lebih baik daripada melepas pengguna ke gua hitam sebelum
     * pemain terpasang. Tidak menunggu video diputar.
     */
    private fun checkPlayerReady() {
        binding.webView.evaluateJavascript(PlayerFocusScript.READ_READY) { raw ->
            if (raw?.trim('"') == "2") dismissSplash()
        }
    }

    /**
     * Pasang ulang skrip halaman secara periodik — tanpa syarat URL: guard
     * membuat pemasangan ulang diam bila skrip masih hidup, jadi ini murah.
     * Tujuannya membangkitkan skrip yang tewas — dokumen baru hasil
     * redirect, renderer yang diturunkan Chromium selagi WebView tertutup
     * layar sapaan, dan sejenisnya.
     */
    private fun reinstallPageScripts() {
        installPageScripts(binding.webView)
    }

    private fun dismissSplash() {
        if (splashDismissed) return
        splashDismissed = true
        splashPulse?.cancel()
        mainHandler.removeCallbacks(splashDotsTask)
        binding.splashContent.animate().translationYBy(-16f).setDuration(340).start()
        binding.splashOverlay.animate()
            .alpha(0f)
            .setDuration(340)
            .withEndAction {
                binding.splashOverlay.visibility = View.GONE
            }
            .start()
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
        // Warna jujur soal status: cue live disorot aksen; teks terakhir
        // yang sudah selesai (✓) dan keadaan menunggu ditampilkan redup,
        // supaya mata tahu mana yang benar-benar sedang tampil di video.
        binding.liveText.setTextColor(
            ContextCompat.getColor(
                this,
                if (PayloadFormatter.hasLiveCue(payload)) {
                    R.color.panel_accent
                } else {
                    R.color.meta_text
                },
            ),
        )

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
        refreshCueBadge()
    }

    /**
     * Angka cue hanya tampil selama video fullscreen. Di tampilan biasa,
     * pojok kiri atas dibiarkan bersih — angka tak mengambang terus dan
     * tidak menutupi hal lain.
     */
    private fun refreshCueBadge() {
        binding.cueBadge.visibility =
            if (inFullscreen && lastCueNumber > 0) View.VISIBLE else View.GONE
    }

    /** Menampilkan pesan singkat di bar (mis. hasil ekspor). */
    private fun say(message: String) {
        binding.liveText.text = message
        binding.liveText.setTextColor(ContextCompat.getColor(this, R.color.panel_accent))
        lastRenderedLine = message
    }

    /**
     * Notifikasi ekspor dengan animasi kecil: memudar masuk, ditahan
     * sebentar, lalu memudar keluar. Tanpa ini pengguna tidak tahu apakah
     * berkas benar-benar tersimpan — teks di bar terlalu jauh dari mata
     * yang sedang menatap pemain.
     */
    private fun announce(message: String, error: Boolean = false) {
        val toast = binding.saveToast
        toast.animate().cancel()
        mainHandler.removeCallbacks(hideToastTask)
        toast.text = message
        toast.setTextColor(
            ContextCompat.getColor(
                this,
                if (error) R.color.error_text else R.color.panel_accent,
            ),
        )
        toast.alpha = 0f
        toast.scaleX = 0.94f
        toast.scaleY = 0.94f
        toast.visibility = View.VISIBLE
        toast.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160)
            .start()
        mainHandler.postDelayed(hideToastTask, 2800)
    }

    private val hideToastTask = Runnable {
        binding.saveToast.animate()
            .alpha(0f)
            .setDuration(240)
            .withEndAction { binding.saveToast.visibility = View.GONE }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        splashPulse?.cancel()
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
        const val EXTRA_COVER = "episode_cover"

        /**
         * Batas aman layar sapaan: pemain tak kunjung terpasang (jaringan
         * lambat atau varian halaman tanpa pemain), layar tetap meluruh —
         * jangan menyandera. Jaringan uji perangkat pernah 43 KB/s dan
         * pemain baru terpasang beberapa detik kemudian, jadi tidak 15.
         */
        const val SPLASH_TIMEOUT_MS = 20000L

        // Ditulis sebagai escape supaya source tetap ASCII.
        const val CHEVRON_UP = "\u25B2"
        const val CHEVRON_DOWN = "\u25BC"
    }
}