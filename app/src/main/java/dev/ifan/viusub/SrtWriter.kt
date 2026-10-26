package dev.ifan.viusub

/**
 * Menyusun file .srt dari rekaman.
 *
 * Formatnya sengaja sama dengan yang dulu dihasilkan userscript:
 *
 * ```
 * 1
 * 00:00:01,246 --> 00:00:03,589
 * teks subtitle
 *
 * 2
 * ...
 * ```
 *
 * Pemisah antar blok adalah satu baris kosong. Beberapa parser SRT menolak
 * file yang blok pertamanya tidak diawali angka, jadi tidak ada komentar
 * atau judul di bagian atas.
 */
object SrtWriter {


    /**
     * Mengubah titik desimal menjadi koma. SRT memakai koma; rekaman
     * sebelumnya pakai titik.
     */
    private fun toSrtTime(value: String?): String {
        if (value.isNullOrBlank()) return "00:00:00,000"
        val dot = value.indexOf('.')
        if (dot < 0) return value
        return value.substring(0, dot) + "," + value.substring(dot + 1)
    }

    /** Mengubah HH:MM:SS.mmm jadi HH-MM-SS-mmm, untuk nama file. */
    private fun toFileStamp(value: String?): String =
        (value ?: "00-00-00-000").replace(':', '-').replace('.', '-')

    /**
     * Nama dasar berkas: judul episode kalau ada, atau nama lama berbasis
     * posisi-durasi kalau judul tidak terbaca.
     *
     * Nama lama dipertahankan sebagai cadangan supaya ekspor tidak pernah
     * menghasilkan berkas bernama kosong ketika halaman belum punya judul.
     */
    fun baseName(payload: VtrPayload, override: String? = null): String =
        FileNames.sanitize(override)
            ?: FileNames.sanitize(payload.title)
            ?: "viu-sub_${toFileStamp(payload.position)}-of-${toFileStamp(payload.total)}"

    fun fileName(payload: VtrPayload, override: String? = null): String =
        "${baseName(payload, override)}.srt"

    fun jsonFileName(payload: VtrPayload, override: String? = null): String =
        "${baseName(payload, override)}.json"

    /**
     * Jumlah baris yang benar-benar akan ditulis ke SRT: cue bernomor
     * ditambah cue live yang juga sudah bernomor. Dipakai penjaga ekspor
     * supaya sesi yang hanya berisi cue "?" tidak menghasilkan file kosong.
     */
    fun numberedCount(payload: VtrPayload): Int =
        payload.cues.count { !it.provisional } +
            payload.pendingCues.count { !it.replay && !it.provisional }

    /**
     * Pemisah antar blok adalah "\n", bukan "\n\n", karena tiap blok sudah
     * diakhiri "\n". Hasilnya satu baris kosong di antara blok, sama seperti
     * file yang dihasilkan userscript. Kalau dipisah "\n\n" akan muncul dua
     * baris kosong dan sebagian parser SRT menolak filenya.
     *
     * Hanya cue bernomor yang ditulis. Cue bertanda "?" berasal dari celah
     * yang belum ditonton: nomornya belum sah, dan SRT tidak punya cara
     * menandai nomor yang belum pasti. Cue tersebut tetap tersimpan di JSON
     * (`unverifiedCues`). Nomor blok ditulis ulang 1..N agar selalu rapat.
     */
    fun build(payload: VtrPayload): String {
        val entries = mutableListOf<VtrPayload.Cue>()
        entries.addAll(payload.cues.filterNot { it.provisional })

        // Cue yang sedang tampil tidak pernah masuk payload.cues; ia hanya
        // ada di pendingCues sampai subtitle hilang dari layar. Tanpa bagian
        // ini, subtitle terakhir yang terlihat (misalnya saat video
        // di-pause di akhir) terdeteksi di panel tapi tidak ikut tersimpan.
        //
        // Cue ulang (replay) tidak pernah masuk cues dan tidak boleh ikut
        // diekspor: isinya sudah ada di cue lama dengan end yang benar.
        //
        // End-nya memakai posisi putar saat ekspor. Kalau posisi belum
        // melewati start, cue dilewati karena durasinya tidak valid.
        val position = payload.position
        if (!position.isNullOrBlank()) {
            payload.pendingCues
                .filterNot { it.replay || it.provisional }
                .forEach { pending ->
                    if (position > pending.start) {
                        entries.add(
                            VtrPayload.Cue(
                                index = 0,
                                start = pending.start,
                                end = position,
                                duration = "",
                                text = pending.text,
                            ),
                        )
                    }
                }
        }

        return entries.mapIndexed { i, cue ->
            "${i + 1}\n${toSrtTime(cue.start)} --> ${toSrtTime(cue.end)}\n${cue.text}\n"
        }.joinToString("\n")
    }
}