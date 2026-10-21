package dev.ifan.viusub

import java.util.Locale

/**
 * Format tampilan untuk panel status dan preview cue.
 *
 * Dipisah dari MainActivity supaya logika format tidak tercampur dengan
 * urusan WebView, polling, dan ekspor.
 */
object PayloadFormatter {

    /** Jumlah cue terakhir yang ditampilkan di preview. */
    private const val PREVIEW_MAX = 30

    // Ditulis sebagai escape supaya source tetap ASCII.
    private const val ELLIPSIS = "\u2026"
    private const val RUNNING = "\u25B8"
    private const val FINISHED = "\u2713"
    private const val REPLAY = "\u21BA"

    fun status(p: VtrPayload): String = buildString {
        append("cue=").append(p.cueCount)
        if (p.restoredCount > 0 && p.cueCount == p.restoredCount) {
            append("  (dipulihkan)")
        }
        val liveCount = p.pendingCues.count { !it.replay }
        val replayCount = p.pendingCues.count { it.replay }
        if (liveCount > 0) append("  (+").append(liveCount).append(" berjalan)")
        if (replayCount > 0) append("  (ulang ").append(replayCount).append(')')

        append('\n')
        append(p.position ?: "--:--:--")
        append(" / ")
        append(p.total ?: "--:--:--")
        if (!p.remaining.isNullOrBlank()) append("  sisa ").append(p.remaining)

        append('\n')
        append("cakupan ").append(p.coveragePercent ?: "-").append('%')
        if (p.gapCount > 0) append("  lubang:").append(p.gapCount)

        p.holes.firstOrNull()?.let { hole ->
            append('\n')
            append("celah rekaman ").append(clock(hole.from)).append(" → ").append(clock(hole.to))
            if (p.provisionalCount > 0) {
                append("  ·  ").append(p.provisionalCount).append(" cue belum bernomor")
            }
        }

        append('\n')
        append(
            when {
                p.gapCount > 0 -> "! ${p.firstGap}"
                p.cueCount > 0 -> "lengkap sejauh ini"
                else -> "menunggu..."
            },
        )

        // Diagnosa: filter mana yang paling banyak membuang kandidat.
        // Berguna saat ada subtitle yang tampil di video tapi tidak terekam.
        append("\n").append(p.filters.summary())

        if (p.resumedFrom > 0) {
            val pos = parseTime(p.position)
            if (pos > 0 && pos < p.resumedFrom - 2) {
                append("\n(putar ulang: duplikat dilewati)")
            }
        }
    }

    /**
     * Waktu ringkas untuk daftar transkrip: mm:ss di bawah satu jam,
     * h:mm:ss di atasnya. Milidetik dibuang karena hanya menambah lebar
     * tanpa berguna saat dibaca sekilas.
     */
    fun clock(value: String?): String {
        if (value.isNullOrBlank()) return "--:--:--"
        return clock(parseTime(value))
    }

    fun clock(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun preview(p: VtrPayload): String {
        val lines = mutableListOf<String>()

        // Cue sudah dipangkas di sisi JS, jadi penanda yang benar adalah
        // truncatedFrom, bukan menghitung ulang dari jumlah cue yang diterima.
        if (p.truncatedFrom > 0) {
            lines.add("$ELLIPSIS ${p.truncatedFrom} cue sebelumnya")
        }
        for (c in p.cues) {
            lines.add("${c.index}. ${c.start}  ${c.text}")
        }
        for (pc in p.pendingCues) {
            val mark = if (pc.replay) "$REPLAY ulang" else RUNNING
            val end = if (pc.replay) " \u2192 ${clock(pc.end)}" else ""
            lines.add("${pc.index}. $mark ${pc.start}$end  ${pc.text}")
        }
        return lines.joinToString("\n")
    }

    /**
     * Satu baris teks untuk bar melayang.
     *
     * Empat keadaan yang dibedakan dengan awalan:
     *   "▸ teks" — cue baru yang benar-benar sedang tampil di video (live);
     *   "↺ teks" — cue lama yang sedang diputar ulang;
     *   "✓ teks" — cue terakhir yang sudah selesai; bar tidak dikosongkan
     *              supaya tidak berkedip di setiap jeda antar dialog,
     *              tetapi ditampilkan dengan warna redup oleh pemanggil
     *              supaya mata tahu itu bukan yang sedang live;
     *   ""       — belum ada cue sama sekali (pemanggil menampilkan
     *              "Menunggu subtitle…").
     */
    fun liveLine(p: VtrPayload): String {
        p.pendingCues.firstOrNull()?.let {
            return if (it.replay) "$REPLAY ${it.text}" else "$RUNNING ${it.text}"
        }
        return p.cues.lastOrNull()?.text?.let { "$FINISHED $it" }.orEmpty()
    }

    /** Apakah ada cue yang benar-benar sedang tampil di video saat ini. */
    fun hasLiveCue(p: VtrPayload): Boolean = p.pendingCues.isNotEmpty()

    /** Mengubah HH:MM:SS.mmm menjadi detik pecahan, 0 kalau tidak terbaca. */
    fun parseTime(value: String?): Double {
        if (value.isNullOrBlank()) return 0.0
        val parts = value.split(':')
        if (parts.size != 3) return 0.0
        return try {
            parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
        } catch (e: Exception) {
            0.0
        }
    }
}