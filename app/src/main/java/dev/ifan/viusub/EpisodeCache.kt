package dev.ifan.viusub

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cache daftar episode di penyimpanan internal app.
 *
 * Tujuannya bukan kecepatan, tapi supaya Home tidak kosong saat tidak ada
 * jaringan. Daftar yang terakhir berhasil diambil tetap ditampilkan, dengan
 * penanda di layar bahwa itu data lama.
 */
object EpisodeCache {

    private const val TAG = "EpisodeCache"
    private const val FILE_NAME = "episodes.json"

    fun save(context: Context, episodes: List<Episode>) {
        try {
            val array = JSONArray()
            for (e in episodes) {
                array.put(
                    JSONObject().apply {
                        put("number", e.number)
                        put("year", e.year)
                        put("date", e.date)
                        put("title", e.title)
                        put("url", e.url)
                        put("cover", e.coverUrl)
                        put("duration", e.durationSeconds)
                    },
                )
            }
            file(context).writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyimpan cache", e)
        }
    }

    fun load(context: Context): List<Episode> {
        val file = file(context)
        if (!file.exists()) return emptyList()

        return try {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val number = o.optInt("number", -1)
                    if (number < 0) continue
                    add(
                        Episode(
                            number = number,
                            year = o.optInt("year", 0),
                            date = o.optString("date"),
                            title = o.optString("title"),
                            url = o.optString("url"),
                            coverUrl = o.optString("cover"),
                            durationSeconds = o.optInt("duration", 0),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membaca cache", e)
            emptyList()
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}