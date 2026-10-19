package dev.ifan.viusub

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pengambil daftar episode dari API Viu.
 *
 * Bentuk alamat dan parameternya bukan tebakan: ini yang dipakai halaman Viu
 * sendiri, dan sudah diverifikasi menghasilkan data pada 19 Oktober 2026.
 * Prefix `/api/` di depan `mobile` itu bagian yang mudah terlewat — tanpa
 * itu semua permintaan menjawab 404.
 */
object ViuApi {

    private const val TAG = "ViuApi"

    private const val ENDPOINT = "https://api-gateway-global.viu.com/api/mobile"

    /** Halaman episode, untuk menyusun tautan yang dibuka pemutar. */
    private const val PAGE = "https://www.viu.com/ott/id/id/vod"

    /**
     * Satu entri per musim. Nomornya adalah `series_id` di Viu, dan `slug`
     * dipakai apa adanya di tautan episode.
     *
     * Urutannya sengaja dari tahun tua ke baru, sesuai urutan yang diminta
     * ditampilkan.
     */
    private data class Season(val year: Int, val seriesId: String, val slug: String)

    private val SEASONS = listOf(
        Season(2024, "72839", "Running-Man-2024"),
        Season(2025, "85365", "Running-Man-2025"),
        Season(2026, "97518", "Running-Man-2026"),
    )

    /**
     * Mengambil seluruh episode dari ketiga musim, digabung dan diurutkan
     * dari yang terlama ke yang terbaru.
     *
     * Musim yang gagal diambil dilewati, bukan menggagalkan semuanya: lebih
     * baik menampilkan dua musim daripada layar kosong karena satu
     * permintaan gagal.
     */
    fun fetchAll(): List<Episode> {
        val result = mutableListOf<Episode>()

        for (season in SEASONS) {
            try {
                result += fetchSeason(season)
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengambil musim ${season.year}", e)
            }
        }

        return result.sortedByDescending { it.number }
    }

    private fun fetchSeason(season: Season): List<Episode> {
        val url = URL(
            "$ENDPOINT?r=/vod/product-list&os_flag_id=1" +
                "&series_id=${season.seriesId}&size=-1&sort=desc",
        )

        val json = httpGet(url.toString())
        val root = JSONObject(json)
        val list = root.optJSONObject("data")?.optJSONArray("product_list") ?: return emptyList()

        val out = mutableListOf<Episode>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val number = item.optString("number").toIntOrNull() ?: continue
            val productId = item.optString("product_id").takeIf { it.isNotBlank() } ?: continue

            val (year, date) = Episode.fromEpoch(item.optString("schedule_start_time").toLongOrNull() ?: 0L)

            out += Episode(
                number = number,
                // Kalau tanggal tidak ada, pakai tahun musimnya supaya
                // episode tidak jatuh ke kelompok "tanpa tahun".
                year = if (year > 0) year else season.year,
                date = date,
                title = item.optString("synopsis").ifBlank { "Episode $number" },
                url = "$PAGE/$productId/${season.slug}",
                coverUrl = item.optString("cover_image_url"),
                durationSeconds = item.optString("time_duration").toIntOrNull() ?: 0,
            )
        }

        return out
    }

    private fun httpGet(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Referer", "https://www.viu.com/")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code dari $address")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}