package dev.ifan.viusub

import org.json.JSONArray
import org.json.JSONObject

/**
 * Merapikan payload sebelum disimpan sebagai JSON.
 *
 * Masalahnya nomor cue. Selama masih ada celah rekaman yang belum ditonton,
 * cue setelah celah belum bisa diberi nomor pasti — di layar ditandai "?".
 * Kalau cue itu tetap ditulis di `cues` dengan `"i": 8`, file JSON
 * menyatakan nomor yang aplikasi sendiri tidak berani tampilkan.
 *
 * Karena itu ekspor memisahkannya:
 *
 * - `cues` hanya berisi cue bernomor, diurutkan ulang 1..N;
 * - `unverifiedCues` berisi cue bertanda "?", tanpa field `i`;
 * - `numberedCount` dan `unverifiedCount` merangkum keduanya.
 *
 * `pendingCues` (cue yang sedang tampil) dibiarkan apa adanya: bagian itu
 * memang sudah terpisah dan berisi durasi sementara.
 */
object ExportJson {

    fun build(json: String): String = try {
        val root = JSONObject(json)
        val cues = root.optJSONArray("cues") ?: JSONArray()
        val numbered = JSONArray()
        val unverified = JSONArray()

        for (i in 0 until cues.length()) {
            val cue = cues.optJSONObject(i) ?: continue
            if (cue.optBoolean("provisional", false)) {
                unverified.put(withoutIndex(cue))
            } else {
                // Nomor ditulis ulang supaya pasti rapat 1..N, walaupun
                // payload datang dari halaman dengan urutan berbeda.
                numbered.put(JSONObject(cue.toString()).put("i", numbered.length() + 1))
            }
        }

        root.put("cues", numbered)
        root.put("numberedCount", numbered.length())
        root.put("unverifiedCount", unverified.length())
        if (unverified.length() > 0) {
            root.put("unverifiedCues", unverified)
        } else {
            root.remove("unverifiedCues")
        }
        root.toString()
    } catch (e: Exception) {
        // Payload rusak tidak boleh menggagalkan ekspor: simpan apa adanya.
        json
    }

    /** Salinan cue tanpa nomor, karena nomornya belum sah. */
    private fun withoutIndex(cue: JSONObject): JSONObject =
        JSONObject(cue.toString()).apply { remove("i") }
}
