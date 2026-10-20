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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.ifan.viusub.databinding.ActivityMainBinding
import org.json.JSONObject

/**
 * Layar pemutar: WebView penuh, dengan bar live tepat di bawah video,
 * panel transkrip mengisi sisa area hitam, dan panel kontrol tetap di
 * dasar layar.
 *
 * Bar live hanya menampilkan satu baris teks subtitle yang sedang tampil.
 * Tombol-tombol (folder, reset, simpan JSON, simpan SRT, status) berada
 * di panel kontrol terpisah, bukan di dalam bar.
 *
 * Tidak ada split 30% lagi. WebView memakai seluruh layar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Mencegah render ulang untuk isi yang sama. */
    private var lastRenderedLine: String? = null

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

    /** Posisi atas bar live (piksel). -1 selama video belum terukur. */
    private var barTopPx = -1
    private var layoutTick = 0
    private var transcriptTick = 0

    private val transcriptAdapter = TranscriptAdapter { seconds -> seekTo(seconds) }

    /**
     * Seluruh cue yang sudah terekam; null selama snapshot penuh belum
     * datang dan panel masih memakai 30 cue terakhir dari polling.
     * Snapshot ini menjadi dasar daftar panjang, lalu cue terbaru dari
     * polling digabung ke dalamnya supaya akhir cue terakhir tetap segar.
     */
    private var allCues: List<VtrPayload.Cue>? = null
    private var loadingAllCues = false

    /**
     * Apakah daftar transkrip sedang mengikuti cue live di puncak.
     *
     * Selama true, setiap kali ada cue baru daftar dikembalikan ke baris
     * pertama. Begitu pengguna menggulir ke bawah untuk membaca cue lama,
     * nilainya false supaya posisi baca tidak direbut. Menekan tombol
     * lingkaran ↑ atau menggulir sendiri sampai puncak menyalakannya lagi.
     */
    private var followLive = true

    /**
     * Naik setiap kali halaman mulai dimuat. Dipakai untuk membuang hasil
     * READ_FULL yang datang terlambat dari halaman sebelumnya.
     */
    private var pageGeneration = 0

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
            if (layoutTick++ % 10 == 0) measureVideoBottom()
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
        setupTranscript()

        if (savedInstanceState == null) {
            startSplash()
            // Layar sapaan dilewati kalau episode tidak membawa judul/cover
            // (mis. WebView dibuka tanpa Home). Tanpa penanda ini, bar dan
            // transkrip tidak akan pernah dianggap siap tampil.
            if (binding.splashOverlay.visibility != View.VISIBLE) splashDismissed = true
            binding.webView.loadUrl(startUrl)
        } else {
            splashDismissed = true
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
     * live bar (plus the transcript panel under it) is hidden because the
     * video now occupies the whole area. The cue number badge appears only
     * while fullscreen, so the normal view stays clean.
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
        binding.controlPanel.visibility =
            if (!enabled && splashDismissed) View.VISIBLE else View.GONE
        updateRecorderPanel()
        binding.controlPanel.post { updateRecorderPanel() }

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
                // Rekaman halaman lama tidak boleh tercampur dengan halaman
                // baru yang indeks cue-nya mulai dari 1 lagi.
                invalidateFullTranscript()
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
        binding.btnInfo.setOnClickListener { showStatusDialog() }

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
            invalidateFullTranscript()
            transcriptAdapter.clear()
            binding.transcriptEmpty.visibility = View.VISIBLE
            binding.cueBadge.visibility = View.GONE
            say(getString(R.string.panel_idle))
        }

        binding.btnSaveJson.setOnClickListener { exportJson() }
        binding.btnSaveSrt.setOnClickListener { exportSrt() }
    }

    /**
     * Panel transkrip di bawah bar: statistik rekaman dan cue yang sudah
     * lewat. Bar live sendiri menjadi kepalanya.
     */
    private fun setupTranscript() {
        binding.transcriptList.layoutManager = LinearLayoutManager(this)
        binding.transcriptList.adapter = transcriptAdapter

        binding.btnJumpLive.setOnClickListener {
            followLive = true
            binding.transcriptList.scrollToPosition(0)
            updateJumpLive()
        }
        binding.transcriptList.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    // Sentuhan pengguna menghentikan mode ikuti-live supaya
                    // daftar tidak melawan saat sedang dibaca. Saat jari
                    // dilepas, posisi sebenarnya yang menentukan: berhenti
                    // di puncak berarti ikuti-live menyala lagi.
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        followLive = false
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        followLive = !recyclerView.canScrollVertically(-1)
                        updateJumpLive()
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // Menggulir sendiri sampai puncak menyalakan lagi mode
                    // ikuti-live; tidak perlu menekan tombol.
                    if (!recyclerView.canScrollVertically(-1)) {
                        followLive = true
                    }
                    updateJumpLive()
                }
            },
        )
    }

    /**
     * Membuang cache transkrip penuh. Dipanggil saat Reset atau saat halaman
     * berganti, karena indeks cue di halaman baru mulai dari 1 lagi.
     */
    private fun invalidateFullTranscript() {
        pageGeneration++
        allCues = null
        loadingAllCues = false
        followLive = true
        updateJumpLive()
    }

    /**
     * Mengambil snapshot penuh di latar belakang. Dipanggil otomatis saat
     * pertama kali atau saat ada celah antara snapshot lama dan cue terbaru;
     * tidak ada tombol "Muat semua" lagi. Polling tetap membawa 30 cue
     * terakhir, jadi permintaan ini tidak terjadi setiap 100 ms.
     */
    private fun loadFullTranscript() {
        if (loadingAllCues) return
        loadingAllCues = true

        val generation = pageGeneration
        binding.webView.evaluateJavascript(RecorderScript.READ_FULL) { raw ->
            if (generation != pageGeneration) return@evaluateJavascript
            loadingAllCues = false

            val payload = VtrPayload.parse(raw?.let { unquote(it) }) ?: return@evaluateJavascript
            allCues = payload.cues
            updateTranscript(payload)
        }
    }

    /**
     * Menggabungkan snapshot penuh dengan cue terbaru dari polling.
     * Polling hanya membawa PREVIEW_MAX cue terakhir; cue lama tetap di
     * tempatnya, sedangkan cue yang sama diambil versi terbaru supaya
     * waktu selesainya ikut diperbarui.
     */
    private fun mergeCues(
        all: List<VtrPayload.Cue>,
        recent: List<VtrPayload.Cue>,
    ): List<VtrPayload.Cue> {
        if (recent.isEmpty()) return all
        val byIndex = LinkedHashMap<Int, VtrPayload.Cue>(all.size + recent.size)
        for (cue in all) byIndex[cue.index] = cue
        for (cue in recent) byIndex[cue.index] = cue
        return byIndex.values.sortedBy { it.index }
    }

    /**
     * Membaca posisi bawah video dari halaman. Dipanggil tidak terlalu
     * sering (sekitar sekali per detik): posisi video hanya berubah saat
     * tata letak halaman berubah, bukan setiap kali subtitle berganti.
     */
    private fun measureVideoBottom() {
        binding.webView.evaluateJavascript(PlayerFocusScript.READ_VIDEO_BOTTOM) { raw ->
            val ratio = raw?.trim()?.toDoubleOrNull() ?: return@evaluateJavascript
            if (ratio > 0.0) placeLiveBar(ratio)
        }
    }

    /** Menempelkan bar live tepat di bawah gambar, dengan jarak tipis. */
    private fun placeLiveBar(ratio: Double) {
        if (inFullscreen) return
        val height = binding.webView.height
        if (height <= 0) return

        val gap = (6 * resources.displayMetrics.density).toInt()
        val target = (ratio * height).toInt() + gap
        if (target == barTopPx) {
            // Posisi tidak berubah, tetapi tinggi panel kontrol bisa
            // berubah (rotasi/font). Perbarui keputusan tampil panel.
            binding.liveBar.post { updateRecorderPanel() }
            return
        }
        barTopPx = target

        val lp = binding.liveBar.layoutParams as ConstraintLayout.LayoutParams
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        lp.topMargin = target
        binding.liveBar.layoutParams = lp
        // Kalau sapaan sudah pergi tetapi bar belum sempat tampil karena
        // posisinya belum terukur, tampilkan sekarang — sudah di tempat benar.
        if (splashDismissed && !inFullscreen) {
            binding.liveBar.visibility = View.VISIBLE
        }
        binding.liveBar.post { updateRecorderPanel() }
    }

    /**
     * Panel hanya ditampilkan kalau pemain sudah terlihat, tidak sedang
     * fullscreen, dan sisa ruang di bawah bar cukup untuk dibaca. Di layar
     * pendek (mis. landscape) area hitam bisa hampir tidak ada, dan panel
     * yang dipaksakan justru menutupi video.
     */
    private fun updateRecorderPanel() {
        val room = binding.controlPanel.top - binding.liveBar.bottom
        val enough = barTopPx > 0 &&
            binding.controlPanel.height > 0 &&
            room >= (140 * resources.displayMetrics.density).toInt()
        val show = splashDismissed && !inFullscreen && enough
        val desired = if (show) View.VISIBLE else View.GONE
        if (binding.recorderPanel.visibility == desired) return

        if (show) {
            binding.recorderPanel.alpha = 0f
            binding.recorderPanel.visibility = View.VISIBLE
            binding.recorderPanel.animate().alpha(1f).setDuration(160).start()
        } else {
            binding.recorderPanel.visibility = View.GONE
        }
    }

    private fun updateTranscript(payload: VtrPayload) {
        binding.transcriptStats.text = PayloadFormatter.transcriptStats(payload)

        // Semua cue yang sudah terekam ditampilkan. Snapshot penuh digabung
        // dengan 30 cue terbaru dari polling; kalau snapshot belum ada atau
        // ada celah, READ_FULL diambil otomatis di latar belakang.
        val cached = allCues
        // Rute SPA bisa mengosongkan perekam tanpa memuat ulang halaman.
        // Kalau jumlah cue menyusut, snapshot lama sudah tidak berlaku.
        if (cached != null && payload.cueCount < cached.size) {
            allCues = null
        }
        val full = allCues
        val cues = if (full == null) {
            payload.cues
        } else {
            mergeCues(full, payload.cues).also { allCues = it }
        }

        val snapshot = allCues
        if (payload.cueCount > 0 &&
            (snapshot == null || payload.cueCount > snapshot.size)
        ) {
            loadFullTranscript()
        }

        val kosong = cues.isEmpty() && payload.pendingCues.isEmpty()
        binding.transcriptEmpty.visibility = if (kosong) View.VISIBLE else View.GONE

        // Urutan terbalik: cue terbaru dan live di puncak. Selama mode
        // ikuti-live menyala, daftar selalu dikembalikan ke puncak setiap
        // ada pembaruan — termasuk saat cue live berganti tanpa jumlah
        // baris berubah. Kalau pengguna sedang membaca cue lama, posisinya
        // tidak direbut.
        transcriptAdapter.submit(cues, payload.pendingCues)
        if (followLive) {
            binding.transcriptList.scrollToPosition(0)
        }
        updateJumpLive()
    }

    /**
     * Tombol "kembali ke live" hanya muncul saat daftar tergulir menjauh
     * dari puncak. Tombol melayang di kanan bawah daftar; padding bawah
     * daftar ditambah hanya selama tombol terlihat supaya baris terakhir
     * tidak tertutup dan tidak ada ruang kosong permanen saat tombol
     * tersembunyi.
     */
    private fun updateJumpLive() {
        val scrolled = !followLive && binding.transcriptList.canScrollVertically(-1)
        val target = if (scrolled) View.VISIBLE else View.GONE
        if (binding.btnJumpLive.visibility != target) {
            binding.btnJumpLive.visibility = target
            if (scrolled) {
                binding.btnJumpLive.alpha = 0f
                binding.btnJumpLive.animate().alpha(1f).setDuration(120).start()
            }
        }

        // 44dp tombol + 12dp margin + 4dp jarak aman.
        val density = resources.displayMetrics.density
        val bottom = if (scrolled) (60 * density).toInt() else (8 * density).toInt()
        if (binding.transcriptList.paddingBottom != bottom) {
            binding.transcriptList.setPadding(
                binding.transcriptList.paddingLeft,
                binding.transcriptList.paddingTop,
                binding.transcriptList.paddingRight,
                bottom,
            )
        }
    }

    /** Melompat ke awal cue yang diketuk di transkrip. */
    private fun seekTo(seconds: Double) {
        binding.webView.evaluateJavascript(RecorderScript.seek(seconds)) { raw ->
            if (raw?.trim('"') == "true") {
                announce(getString(R.string.msg_seek, PayloadFormatter.clock(seconds)))
            } else {
                announce(getString(R.string.msg_seek_failed), error = true)
            }
        }
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

    /**
     * Status lengkap perekam, termasuk ringkasan filter, ditampilkan lewat
     * dialog supaya tidak memenuhi layar utama. Data diambil dari payload
     * terakhir; kalau belum ada, tampilkan keterangan singkat.
     */
    private fun showStatusDialog() {
        val payload = lastPayload
        val message = if (payload == null) {
            getString(R.string.status_empty)
        } else {
            PayloadFormatter.status(payload)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.status_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        // Bar dan transkrip belum punya posisi yang benar sebelum video
        // terukur; menampilkannya sekarang hanya membuat lompatan.
        binding.liveBar.visibility = View.INVISIBLE
        binding.recorderPanel.visibility = View.GONE
        binding.controlPanel.visibility = View.INVISIBLE

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
        // Jangan tampilkan bar di dasar layar lalu melompat: ukur dulu,
        // dan placeLiveBar() yang menampilkannya begitu posisinya benar.
        measureVideoBottom()
        if (!inFullscreen && barTopPx > 0) binding.liveBar.visibility = View.VISIBLE
        if (!inFullscreen) binding.controlPanel.visibility = View.VISIBLE
        updateRecorderPanel()
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

        // Transkrip cukup diperbarui dua kali per detik; payload tetap
        // dibaca sepuluh kali per detik untuk bar live.
        if (binding.recorderPanel.visibility == View.VISIBLE && transcriptTick++ % 5 == 0) {
            updateTranscript(payload)
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
    }
}