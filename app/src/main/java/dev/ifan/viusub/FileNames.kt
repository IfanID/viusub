package dev.ifan.viusub

/**
 * Pembersih nama berkas hasil ekspor.
 *
 * Judul episode di Viu berbentuk seperti:
 *   "Running Man (2024) Episode 733｜Variety Show Korea｜Viu Indonesia"
 *
 * Yang dipakai hanya bagian pertama sebelum pemisah, karena sisanya adalah
 * nama acara dan platform — panjang dan berulang di setiap episode.
 *
 * Nama berkas tidak boleh memuat karakter terlarang, dan SAF menolak nama
 * yang terlalu panjang, jadi hasilnya dipangkas.
 */
object FileNames {

    /**
     * Batas panjang. Nama berkas di Android aman sampai 255 byte, tapi nama
     * sepanjang itu tidak berguna dan sebagian provider menolaknya lebih
     * awal. 80 karakter sudah memuat judul episode lengkap.
     */
    private const val MAX_LENGTH = 80

    /** Karakter yang tidak boleh ada di nama berkas, plus karakter kontrol. */
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")
    private val CONTROL = Regex("""[\u0000-\u001F\u007F]""")
    private val SPACES = Regex("""\s+""")

    /** Pemisah yang dipakai Viu di judul halaman: ｜ (penuh) dan | (biasa). */
    private const val SEPARATOR_FULL = '\uFF5C'

    /**
     * Mengembalikan nama yang aman, atau null kalau tidak ada yang tersisa.
     * Null berarti pemanggil harus memakai nama cadangan, bukan nama kosong.
     */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        var value = raw
        val cut = value.indexOf(SEPARATOR_FULL)
        if (cut >= 0) value = value.substring(0, cut)
        value = value.substringBefore('|')

        value = ILLEGAL.replace(value, " ")
        value = CONTROL.replace(value, "")
        value = SPACES.replace(value, " ").trim()

        if (value.isBlank()) return null

        return value.take(MAX_LENGTH).trim().ifBlank { null }
    }
}