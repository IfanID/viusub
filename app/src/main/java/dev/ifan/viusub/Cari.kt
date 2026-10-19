package dev.ifan.viusub

import java.text.Normalizer

/**
 * Aturan pencocokan dan penilaian pencarian.
 *
 * Ini port dari `shared/utils/cari.ts` milik rout-web, supaya perilakunya
 * sama: normalisasi yang menyamakan huruf besar dan diakritik, semua token
 * wajib cocok, dan skor berjenjang dari kecocokan paling utuh ke paling
 * longgar.
 *
 * Semua fungsi di sini murni dan tidak menyentuh Android, jadi bisa diuji
 * terpisah dari UI.
 */
object Cari {

    private val PEMISAH = Regex("""[^\p{L}\p{N}]+""")
    private val DIAKRITIK = Regex("""\p{M}+""")
    private val SPASI = Regex("""\s+""")
    private val HANGUL = Regex("""[\u3131-\uD7A3]""")

    /** Satu bidang teks yang boleh dicocokkan, sudah dinormalisasi. */
    data class Bidang(
        val cari: String,
        val bobot: Float,
        val utuh: Boolean = false,
    ) {
        /** Bentuk tanpa spasi, hanya dipakai kalau [utuh] benar. */
        val cariUtuh: String = if (utuh) cari.replace(" ", "") else ""
    }

    /** Kueri yang sudah dipecah, dipakai berulang untuk semua kandidat. */
    data class Kueri(
        val asli: String,
        val q: String,
        val qUtuh: String,
        val token: List<String>,
    )

    /**
     * Bentuk baku untuk pencocokan, bukan untuk ditampilkan.
     *
     * Urutan langkahnya penting: NFC dulu supaya Hangul tetap bulat, lalu
     * huruf kecil, lalu NFD hanya untuk membuang tanda diakritik Latin,
     * lalu NFC lagi. Kalau NFD dijalankan paling akhir, Hangul ikut
     * terpecah menjadi jamo dan tidak lagi cocok dengan kueri yang ditulis
     * sebagai suku kata.
     */
    fun normalisasi(teks: String?): String {
        if (teks.isNullOrEmpty()) return ""
        var s = Normalizer.normalize(teks, Normalizer.Form.NFC)
        s = s.lowercase()
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = DIAKRITIK.replace(s, "")
        s = Normalizer.normalize(s, Normalizer.Form.NFC)
        s = PEMISAH.replace(s, " ")
        return SPASI.replace(s.trim(), " ")
    }

    fun siapkanKueri(teks: String): Kueri {
        val q = normalisasi(teks)
        val token = if (q.isBlank()) emptyList() else q.split(" ").distinct()
        return Kueri(asli = teks, q = q, qUtuh = token.joinToString(""), token = token)
    }

    fun buatBidang(teks: String?, bobot: Float = 1f, utuh: Boolean = false): Bidang =
        Bidang(cari = normalisasi(teks), bobot = bobot, utuh = utuh)

    /**
     * Apakah [token] muncul di [teks].
     *
     * Token yang seluruhnya angka harus cocok sebagai angka utuh, bukan
     * potongan di dalam angka lain. Tanpa aturan ini, kueri "25" ikut
     * mencocokkan episode 725 maupun tanggal 2025-08-25 — hasilnya puluhan
     * baris yang tidak ada hubungannya dengan yang diketik.
     */
    private fun cocok(teks: String, token: String): Boolean {
        if (!token.all { it.isDigit() }) return teks.contains(token)
        return Regex("(?<!\\d)" + Regex.escape(token) + "(?!\\d)").containsMatchIn(teks)
    }

    /**
     * Nilai satu bidang untuk satu kueri. Jarak angkanya sengaja lebar
     * supaya perbedaan jenis kecocokan tidak kalah oleh selisih bobot.
     */
    private fun nilaiPola(teks: String, kueri: String): Int {
        if (teks.isEmpty()) return 0

        // Kueri angka memakai batas angka, bukan batas kata: "2025" harus
        // cocok di tanggal "2025-12-29", dan "725" cukup untuk episode 725.
        if (kueri.all { it.isDigit() }) {
            val pola = Regex("(?<!\\d)" + Regex.escape(kueri) + "(?!\\d)")
            val m = pola.find(teks) ?: return 0
            if (m.range.first == 0 && m.range.last == teks.length - 1) return 1200
            val sebelum = if (m.range.first == 0) ' ' else teks[m.range.first - 1]
            return if (sebelum == ' ' || sebelum == '-') {
                1000 - minOf(80, teks.length - kueri.length)
            } else {
                850
            }
        }

        if (teks == kueri) return 1200
        if (teks.startsWith(kueri)) return 1000 - minOf(80, teks.length - kueri.length)
        if (teks.contains(" $kueri")) return 850
        if (teks.contains(kueri)) return 700
        return 0
    }

    private fun semuaTokenDi(kueri: Kueri, teks: String): Boolean =
        kueri.token.all { cocok(teks, it) }

    private fun semuaDiAwalKata(kueri: Kueri, teks: String): Boolean {
        val kata = teks.split(" ")
        return kueri.token.all { satu -> kata.any { it.startsWith(satu) } }
    }

    /** Skor satu kandidat, atau null kalau tidak cocok sama sekali. */
    fun skorBidang(kueri: Kueri, bidang: List<Bidang>): Int? {
        if (kueri.token.isEmpty()) return null

        // Semua token wajib muncul di salah satu bidang. Ini yang menjaga
        // "running man" tidak memunculkan semua episode hanya karena kata
        // "man" kebetulan ada.
        val adaDiSemua = kueri.token.all { satu ->
            bidang.any { cocok(it.cari, satu) || it.cariUtuh.contains(satu) }
        }
        if (!adaDiSemua) return null

        var terbaik = 0f
        var tambahan = 0f

        for (b in bidang) {
            var nilai = 0

            if (b.cari.isNotEmpty()) {
                nilai = nilaiPola(b.cari, kueri.q)

                // Tidak ada kecocokan utuh, tapi semua token tersebar di
                // bidang ini. Masih relevan, hanya lebih lemah.
                if (nilai == 0 && semuaTokenDi(kueri, b.cari)) {
                    nilai = if (semuaDiAwalKata(kueri, b.cari)) 620 else 520
                }

                // Bentuk tanpa spasi. Batas dua karakter supaya satu huruf
                // tidak mencocokkan hampir semua judul.
                if (b.utuh && kueri.qUtuh.length >= 2) {
                    val utuh = if (b.cariUtuh.isNotEmpty()) b.cariUtuh else b.cari.replace(" ", "")
                    nilai = when {
                        utuh == kueri.qUtuh -> maxOf(nilai, 1150)
                        utuh.startsWith(kueri.qUtuh) -> maxOf(nilai, 960)
                        utuh.contains(kueri.qUtuh) -> maxOf(nilai, 680)
                        else -> nilai
                    }
                }
            }

            val berbobot = nilai * b.bobot

            if (berbobot > terbaik) {
                tambahan += terbaik * 0.15f
                terbaik = berbobot
            } else {
                tambahan += berbobot * 0.15f
            }
        }

        return Math.round(terbaik + minOf(140f, tambahan))
    }

    /**
     * Kueri yang terlalu pendek untuk dicari.
     *
     * Satu huruf Latin hampir selalu menghasilkan terlalu banyak hal yang
     * tidak berhubungan. Satu angka tetap berguna (nomor episode), dan satu
     * suku kata Hangul membawa informasi lebih banyak daripada satu huruf.
     */
    fun terlaluPendek(kueri: String): Boolean {
        val q = normalisasi(kueri)
        if (q.isEmpty()) return false
        if (q.length >= 2) return false
        if (q.any { it.isDigit() }) return false
        if (HANGUL.containsMatchIn(q)) return false
        return true
    }

    /** Kandidat yang sudah punya skor, untuk diurutkan. */
    data class Hasil<T>(val item: T, val skor: Int, val tanggal: String, val kunci: String)

    /**
     * Urutan hasil: skor menurun, lalu tanggal terbaru, lalu kunci.
     * Tie-break terakhir membuat urutan tidak bergantung pada urutan
     * larik sumber, jadi hasilnya sama setiap kali.
     */
    fun <T> urutkan(hasil: List<Hasil<T>>): List<Hasil<T>> = hasil.sortedWith(
        compareByDescending<Hasil<T>> { it.skor }
            .thenByDescending { it.tanggal }
            .thenBy { it.kunci },
    )
}