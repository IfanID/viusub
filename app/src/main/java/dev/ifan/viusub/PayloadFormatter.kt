package dev.ifan.viusub

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

    fun status(p: VtrPayload): String = buildString {
        append("cue=").append(p.cueCount)
        if (p.restoredCount > 0 && p.cueCount == p.restoredCount) {
            append("  (dipulihkan)")
        }
        if (p.pending > 0) append("  (+").append(p.pending).append(" berjalan)")

        append('\n')
        append(p.position ?: "--:--:--")
        append(" / ")
        append(p.total ?: "--:--:--")
        if (!p.remaining.isNullOrBlank()) append("  sisa ").append(p.remaining)

        append('\n')
        append("cakupan ").append(p.coveragePercent ?: "-").append('%')
        if (p.gapCount > 0) append("  lubang:").append(p.gapCount)

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
            lines.add("${pc.index}. $RUNNING ${pc.start}  ${pc.text}")
        }
        return lines.joinToString("\n")
    }

    /**
     * Satu baris teks yang sedang tampil di video.
     *
     * Utamakan cue yang masih berjalan (pending), karena itulah yang benar-benar
     * terlihat sekarang. Kalau sedang jeda antar subtitle, pakai cue terakhir
     * yang sudah selesai supaya bar tidak kosong melompong.
     */
    fun liveLine(p: VtrPayload): String {
        p.pendingCues.firstOrNull()?.let { return "$RUNNING ${it.text}" }
        return p.cues.lastOrNull()?.text.orEmpty()
    }

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