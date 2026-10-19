package dev.ifan.viusub

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bentuk data yang dikirim perekam lewat payload().
 *
 * Semua field dibuat nullable-atypa atau punya nilai default, karena isi
 * payload datang dari halaman yang bisa saja belum selesai memasang skrip.
 * Parse yang gagal harus mengembalikan null, bukan melempar, supaya satu
 * payload rusak tidak menjatuhkan seluruh UI.
 */
data class VtrPayload(
    val source: String?,
    val title: String?,
    val method: String?,
    val capturedAt: String?,
    val position: String?,
    val total: String?,
    val remaining: String?,
    val cueCount: Int,
    val pending: Int,
    val coveragePercent: String?,
    val covered: String?,
    val gapCount: Int,
    val firstGap: String?,
    val resumedFrom: Double,
    val restoredCount: Int,
    val truncatedFrom: Int,
    val filters: Filters,
    val cues: List<Cue>,
    val pendingCues: List<Cue>,
) {
    /** Jumlah kandidat yang ditolak tiap filter pada scan terakhir. */
    data class Filters(
        val bmpui: Int,
        val leaf: Int,
        val empty: Int,
        val long: Int,
        val symbol: Int,
        val timecode: Int,
        val closest: Int,
        val geometry: Int,
    ) {
        fun summary(): String =
            "tolak leaf=$leaf bmpui=$bmpui closest=$closest geo=$geometry " +
                "long=$long sym=$symbol kosong=$empty tc=$timecode"
    }

    data class Cue(
        val index: Int,
        val start: String,
        val end: String,
        val duration: String,
        val text: String,
    )

    companion object {
        /**
         * Mengembalikan null kalau JSON tidak valid atau bentuknya tidak
         * dikenal.
         */
        fun parse(json: String?): VtrPayload? {
            if (json.isNullOrBlank() || json.length > MAX_JSON) return null
            return try {
                val root = JSONObject(json)
                val coverage = root.optJSONObject("coverage")

                VtrPayload(
                    source = root.optString("source").takeIf { it.isNotBlank() },
                    title = root.optString("title").takeIf { it.isNotBlank() },
                    method = root.optString("method").takeIf { it.isNotBlank() },
                    capturedAt = root.optString("capturedAt").takeIf { it.isNotBlank() },
                    position = root.optString("position").takeIf { it.isNotBlank() },
                    total = root.optString("total").takeIf { it.isNotBlank() },
                    remaining = root.optString("remaining").takeIf { it.isNotBlank() },
                    cueCount = root.optInt("cueCount", 0),
                    pending = root.optInt("pending", 0),
                    coveragePercent = coverage?.optString("percent")?.takeIf { it.isNotBlank() },
                    covered = coverage?.optString("covered")?.takeIf { it.isNotBlank() },
                    gapCount = coverage?.optInt("gapCount", 0) ?: 0,
                    firstGap = coverage?.optJSONArray("gaps")
                        ?.optJSONObject(0)
                        ?.let { "${it.optString("from")} - ${it.optString("to")}" },
                    resumedFrom = root.optDouble("resumedFrom", 0.0),
                    restoredCount = root.optInt("restoredCount", 0),
                    truncatedFrom = root.optInt("truncatedFrom", 0),
                    filters = root.optJSONObject("filters")?.let { f ->
                        Filters(
                            bmpui = f.optInt("bmpui", 0),
                            leaf = f.optInt("leaf", 0),
                            empty = f.optInt("empty", 0),
                            long = f.optInt("long", 0),
                            symbol = f.optInt("symbol", 0),
                            timecode = f.optInt("timecode", 0),
                            closest = f.optInt("closest", 0),
                            geometry = f.optInt("geometry", 0),
                        )
                    } ?: Filters(0, 0, 0, 0, 0, 0, 0, 0),
                    cues = parseCues(root.optJSONArray("cues")),
                    pendingCues = parseCues(root.optJSONArray("pendingCues")),
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun parseCues(array: JSONArray?): List<Cue> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val c = array.optJSONObject(i) ?: continue
                    add(
                        Cue(
                            index = c.optInt("i", i + 1),
                            start = c.optString("start", "--:--:--"),
                            end = c.optString("end", ""),
                            duration = c.optString("duration", ""),
                            text = c.optString("text", ""),
                        ),
                    )
                }
            }
        }

        /** 4 MB. Di atas ini payload sudah rusak, jangan diparse. */
        private const val MAX_JSON = 4 * 1024 * 1024
    }
}