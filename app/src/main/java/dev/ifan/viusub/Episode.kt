package dev.ifan.viusub

import androidx.annotation.Keep

/**
 * Satu episode Running Man seperti yang ditampilkan di Home.
 *
 * [year] diambil dari tanggal tayang, bukan dari nomor episode: penomoran
 * berlanjut lintas tahun, jadi nomor saja tidak bisa menentukan tahun.
 */
@Keep
data class Episode(
    val number: Int,
    val year: Int,
    /** Tanggal tayang dalam bentuk yyyy-MM-dd, kosong kalau tidak ada. */
    val date: String,
    val title: String,
    val url: String,
    val coverUrl: String,
    val durationSeconds: Int,
) {
    /** Durasi sebagai HH:MM:SS, atau string kosong kalau tidak diketahui. */
    fun duration(): String {
        if (durationSeconds <= 0) return ""
        val h = durationSeconds / 3600
        val m = (durationSeconds % 3600) / 60
        val s = durationSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    companion object {
        /**
         * Mengubah detik tayang (epoch) menjadi tahun dan tanggal.
         * Zona waktu dipaksa UTC supaya hasilnya tidak bergeser satu hari
         * atau satu tahun di perangkat yang zonanya berbeda.
         */
        fun fromEpoch(seconds: Long): Pair<Int, String> {
            if (seconds <= 0) return 0 to ""
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = java.util.Date(seconds * 1000)
            return format.format(date).substring(0, 4).toIntOrNull().let {
                (it ?: 0) to format.format(date)
            }
        }
    }
}