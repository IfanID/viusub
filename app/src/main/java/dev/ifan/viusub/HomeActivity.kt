package dev.ifan.viusub

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import dev.ifan.viusub.databinding.ActivityHomeBinding
import java.util.concurrent.Executors

/**
 * Home: daftar episode Running Man 2024–2026, dengan pencarian langsung.
 *
 * Pencarian berjalan di memori atas daftar yang sudah dimuat, memakai aturan
 * [Cari] yang merupakan port dari rout-web. Tidak ada permintaan jaringan
 * per ketikan: daftarnya hanya 98 episode, jadi menyaring seluruhnya setiap
 * perubahan teks jauh lebih cepat daripada menunggu server.
 *
 * Sumber daftar tetap API Viu. Cache lokal dipakai lebih dulu supaya layar
 * tidak kosong saat tidak ada jaringan.
 */
class HomeActivity : AppCompatActivity() {

    /**
     * Episode beserta judul yang sudah dinormalisasi sekali.
     *
     * Normalisasi dijalankan saat daftar dimuat, bukan tiap ketikan:
     * 98 judul dikali setiap huruf yang diketik itu pemborosan yang tidak
     * mengubah hasil.
     */
    private data class Indexed(
        val episode: Episode,
        val judul: Cari.Bidang,
    )

    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: EpisodeAdapter
    private lateinit var stickyYear: StickyYearOverlay

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var loading = false

    /** Seluruh episode yang sudah dimuat, sebelum disaring kueri. */
    private var all: List<Episode> = emptyList()

    /** Versi siap-cari. Dinormalisasi sekali saat daftar berubah. */
    private var indexed: List<Indexed> = emptyList()

    /** Teks status terakhir dari pemuatan, dipakai lagi saat kueri dikosongkan. */
    private var baseStatus: String = ""

    /** Warna titik status untuk pesan dasar di atas. */
    private var baseTone: Int = R.color.panel_accent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        adapter = EpisodeAdapter { episode -> openEpisode(episode) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        stickyYear = StickyYearOverlay(binding.list, binding.stickyYear, adapter)

        binding.btnRefresh.setOnClickListener { refresh() }

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val adaTeks = !s.isNullOrEmpty()
                updateClearIcon(adaTeks)
                applyQuery(s?.toString().orEmpty())
            }
        })

        // Tombol X dipasang sebagai drawable di ujung kanan kotak. Karena
        // bukan View sendiri, ketukannya harus dideteksi manual: hanya
        // dianggap tekan X kalau titik sentuh berada di dalam gambar itu,
        // supaya mengetuk bagian lain kotak tetap memunculkan keyboard.
        binding.search.setOnTouchListener { view, event ->
            if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener false

            val drawable = binding.search.compoundDrawablesRelative[2] ?: return@setOnTouchListener false
            val batasKiri = binding.search.width -
                binding.search.paddingEnd -
                drawable.bounds.width()

            if (event.x >= batasKiri) {
                binding.search.text.clear()
                true
            } else {
                false
            }
        }

        // Tampilkan cache lebih dulu, lalu perbarui.
        val cached = EpisodeCache.load(this)
        if (cached.isNotEmpty()) {
            setEpisodes(cached)
            baseStatus = getString(R.string.home_cached, cached.size)
            baseTone = R.color.panel_accent
            setStatus(baseStatus, baseTone)
        }

        refresh()
    }

    /**
     * Menampilkan pesan tengah saat daftar kosong. Tanpa ini, layar kosong
     * terlihat seperti app yang gagal memuat.
     */
    private fun updateEmptyState() {
        val kosong = adapter.count() == 0
        binding.emptyState.visibility = if (kosong) View.VISIBLE else View.GONE
        binding.emptyState.text = if (all.isEmpty()) {
            getString(R.string.home_empty_initial)
        } else {
            getString(R.string.home_empty)
        }
        stickyYear.updateAfterLayout()
    }

    /** Menampilkan tombol X hanya saat kotak cari berisi teks. */
    private fun updateClearIcon(adaTeks: Boolean) {
        binding.search.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (adaTeks) R.drawable.ic_clear else 0,
            0,
        )
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            // IME harus dihitung terpisah. Dengan targetSdk 35+ jendela
            // digambar edge-to-edge, jadi adjustResize tidak mengecilkan
            // layout sendiri: kalau inset keyboard tidak dipakai sebagai
            // padding, kotak cari tertutup keyboard.
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

    /**
     * Menyimpan daftar baru dan membangun indeks cari sekali.
     *
     * Normalisasi dijalankan di sini, bukan per ketikan: 98 judul dikali
     * setiap huruf yang diketik itu pemborosan yang tidak mengubah hasil.
     */
    private fun setEpisodes(episodes: List<Episode>) {
        all = episodes
        updateRange()
        indexed = episodes.map { episode ->
            Indexed(
                episode = episode,
                judul = Cari.buatBidang(episode.title, bobot = 1f),
            )
        }
    }

    /**
     * Menampilkan episode terlama dan terbaru.
     *
     * Dihitung dari daftar yang benar-benar dimuat, bukan angka tetap:
     * begitu Viu menayangkan episode baru, angkanya ikut berubah sendiri
     * tanpa perlu menyentuh kode.
     */
    private fun updateRange() {
        if (all.isEmpty()) {
            binding.range.text = getString(R.string.home_range_empty)
            return
        }
        val terlama = all.minOf { it.number }
        val terbaru = all.maxOf { it.number }
        binding.range.text =
            getString(R.string.home_range_count, all.size, terlama, terbaru)
    }

    /**
     * Menerapkan kueri ke daftar yang sudah ada.
     *
     * Aturan "terlalu pendek" ikut dipakai: satu huruf Latin tidak
     * disaring, karena hasilnya akan mencakup hampir semua episode dan
     * terasa seperti pencarian yang rusak. Angka dan Hangul tetap boleh
     * satu karakter.
     */
    private fun applyQuery(raw: String) {
        val query = raw.trim()

        if (query.isEmpty()) {
            adapter.submit(all)
            setStatus(baseStatus, baseTone)
            updateEmptyState()
            return
        }

        if (Cari.terlaluPendek(query)) {
            adapter.submit(all)
            setStatus(getString(R.string.home_query_short), R.color.provisional_mark)
            updateEmptyState()
            return
        }

        // Angka dicocokkan ke nomor episode saja. Teks dicocokkan ke judul.
        // Tanggal tidak ikut dicari sama sekali: kueri "25" akan
        // mencocokkan semua episode yang tayang tanggal 25, dan itu bukan
        // yang dimaksud saat mengetik angka.
        val hasil: List<Episode>

        if (query.all { it.isDigit() }) {
            // Potongan, bukan awalan. Rentang episode hanya 725-823, jadi
            // pencocokan awalan membuat "4" atau "5" tidak menemukan apa
            // pun padahal nomor seperti 745 dan 756 ada. Karena yang
            // dicocokkan hanya kolom nomor, potongan di sini tidak akan
            // memunculkan hal yang tidak berhubungan seperti dulu saat
            // kolom tanggal masih ikut dicari.
            hasil = all.filter { it.number.toString().contains(query) }
                .sortedByDescending { it.number }
        } else {
            val kueri = Cari.siapkanKueri(query)
            val scored = indexed.mapNotNull { entry ->
                val skor = Cari.skorBidang(kueri, listOf(entry.judul))
                    ?: return@mapNotNull null
                Cari.Hasil(
                    item = entry.episode,
                    skor = skor,
                    tanggal = entry.episode.date,
                    kunci = entry.episode.number.toString(),
                )
            }
            hasil = Cari.urutkan(scored).map { it.item }
        }

        if (hasil.isEmpty()) {
            adapter.submitSearch(emptyList(), query)
            setStatus(
                getString(R.string.home_query_empty, query),
                R.color.provisional_mark,
            )
            updateEmptyState()
            return
        }

        adapter.submitSearch(hasil, query)
        setStatus(
            getString(R.string.home_query_results, hasil.size),
            R.color.panel_accent,
        )
        updateEmptyState()
    }

    /**
     * Status di dasar Home selalu punya titik warna. Warna membuat jenis
     * pesan cepat terbaca: cyan untuk alur normal, amber untuk pencarian
     * yang belum lengkap/offline, dan merah untuk kegagalan total.
     */
    private fun setStatus(text: String, tone: Int = R.color.panel_accent) {
        binding.status.text = text
        binding.statusDot.visibility = if (text.isBlank()) View.INVISIBLE else View.VISIBLE
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, tone))
    }

    /** Umpan balik visual saat daftar sedang diambil ulang. */
    private fun setRefreshing(refreshing: Boolean) {
        binding.btnRefresh.isEnabled = !refreshing
        binding.btnRefresh.alpha = if (refreshing) 0.35f else 1f
        binding.refreshProgress.visibility = if (refreshing) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        if (loading) return
        loading = true
        setStatus(getString(R.string.home_loading), R.color.panel_accent)
        setRefreshing(true)

        worker.execute {
            val result = try {
                ViuApi.fetchAll()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengambil daftar episode", e)
                emptyList()
            }

            main.post {
                loading = false
                setRefreshing(false)

                if (result.isEmpty()) {
                    // Jangan hapus yang sedang tampil: cache lebih baik
                    // daripada layar kosong.
                    baseTone = if (all.isNotEmpty()) {
                        R.color.provisional_mark
                    } else {
                        R.color.error_text
                    }
                    baseStatus = if (all.isNotEmpty()) {
                        getString(R.string.home_offline, all.size)
                    } else {
                        getString(R.string.home_failed)
                    }
                    setStatus(baseStatus, baseTone)
                    return@post
                }

                setEpisodes(result)
                EpisodeCache.save(this, result)
                baseStatus = getString(R.string.home_loaded, result.size)
                baseTone = R.color.panel_accent

                // Kalau pengguna sedang mengetik, jangan timpa hasilnya.
                applyQuery(binding.search.text?.toString().orEmpty())
            }
        }
    }

    private fun openEpisode(episode: Episode) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_URL, episode.url)
            putExtra(MainActivity.EXTRA_TITLE, episode.title)
            putExtra(MainActivity.EXTRA_COVER, episode.coverUrl)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "HomeActivity"
    }
}