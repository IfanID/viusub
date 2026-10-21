package dev.ifan.viusub

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.ifan.viusub.databinding.ItemTranscriptBinding

/**
 * Daftar cue di panel rekaman.
 *
 * Urutannya terbalik: cue paling baru di puncak, cue lama di bawah. Cue
 * yang masih live (dari payload pendingCues) selalu berada di baris
 * pertama, dekat dengan video, dan diberi latar cyan tipis plus ikon
 * siaran di kolom nomor supaya jelas mana yang sedang tampil. Cue yang
 * belum bernomor memakai tanda ? amber; cue ulang memakai ↺ diikuti
 * nomor lamanya.
 *
 * Cue yang sedang diputar ulang juga tampil di puncak, tetapi memakai
 * nomor, waktu mulai, dan waktu selesai cue lama. Baris lamanya tidak
 * ditampilkan dua kali selama mode ulang aktif.
 */
class TranscriptAdapter(
    private val context: Context,
    private val onSeek: (Double) -> Unit,
    private val onFillHole: (Double) -> Unit = {},
) : RecyclerView.Adapter<TranscriptAdapter.Holder>() {

    data class Row(
        val index: Int,
        val start: String,
        val clock: String,
        val endClock: String?,
        val text: String,
        val live: Boolean,
        val replay: Boolean = false,
        val marker: Boolean = false,
        /** Baris celah rekaman yang bisa diketuk untuk mulai mengisi. */
        val gap: Boolean = false,
        val gapStart: Double = 0.0,
        /** Nomor belum pasti karena ada celah sebelum cue ini. */
        val provisional: Boolean = false,
    )

    private val rows = mutableListOf<Row>()

    /**
     * Jumlah cue selesai saat lompatan terakhir. Baris dengan nomor di atas
     * nilai ini adalah cue yang baru terekam setelah lompatan; baris di
     * bawahnya adalah cue yang sudah ada sebelumnya. Nol berarti belum ada
     * lompatan, jadi tidak ada garis pemisah.
     */
    private var seekMark = 0
    private var seekClock: String? = null

    fun markSeek(cueCount: Int, clock: String) {
        seekMark = cueCount.coerceAtLeast(0)
        seekClock = clock
    }

    fun clearSeekMark() {
        seekMark = 0
        seekClock = null
    }

    /** Mengganti isi daftar tanpa kehilangan posisi gulir kalau tidak berubah. */
    fun submit(
        cues: List<VtrPayload.Cue>,
        pending: List<VtrPayload.Cue>,
        position: Double,
        holes: List<VtrPayload.Hole> = emptyList(),
        provisionalCount: Int = 0,
    ) {
        // Garis pemisah hanya berguna selama cue lama belum terlewati.
        // Setelah posisi video melewati cue lama terakhir, semua cue di
        // depan adalah rekaman baru, jadi garisnya dihilangkan.
        val boundaryCue = cues.lastOrNull { it.index <= seekMark }
        val boundaryEnd = boundaryCue?.end?.let(PayloadFormatter::parseTime) ?: 0.0
        val showSeekMark = seekMark > 0 &&
            !(boundaryEnd > 0 && position >= boundaryEnd)

        // Cue ulang memakai nomor cue lama. Jangan tampilkan cue lama itu
        // dua kali: sekali sebagai baris live di puncak, sekali di posisi
        // aslinya di bawah.
        val replayIndices = pending.filter { it.replay }.mapTo(HashSet()) { it.index }
        val shownReplay = HashSet<Int>()

        val firstHole = holes.firstOrNull()
        // payload.provisionalCount menghitung seluruh cue, bukan hanya yang
        // ikut dalam potongan polling 30 cue terakhir.
        // Cue ulang adalah duplikat cue lama; jangan dihitung dua kali.
        val localProvisional =
            cues.count { it.provisional } + pending.count { it.provisional && !it.replay }
        val provisionalTotal = if (provisionalCount > 0) provisionalCount else localProvisional
        // Baris celah menggantikan penanda lompatan lama: keduanya
        // menceritakan hal yang sama, jadi jangan tampil berdampingan.
        val showGap = firstHole != null && provisionalTotal > 0
        val showMarker = showSeekMark && !showGap

        val next = buildList {
            // Urutan terbalik: yang paling baru di atas, yang lama di bawah.
            // Cue live selalu berada di puncak supaya dekat dengan video.
            for (c in pending) {
                if (c.replay && !shownReplay.add(c.index)) continue
                add(
                    Row(
                        index = c.index,
                        start = c.start,
                        clock = PayloadFormatter.clock(c.start),
                        // Cue ulang sudah punya end dari rekaman lama.
                        endClock = c.end.takeIf { it.isNotBlank() }
                            ?.let(PayloadFormatter::clock),
                        text = c.text,
                        live = true,
                        replay = c.replay,
                        provisional = c.provisional,
                    ),
                )
            }
            var markerPlaced = false
            var gapPlaced = false
            for (c in cues.asReversed()) {
                if (c.index in replayIndices) continue
                // Baris celah diletakkan di batas antara cue yang belum
                // bernomor dan cue yang sudah bernomor.
                if (showGap && !gapPlaced && !c.provisional) {
                    add(gapRow(firstHole, provisionalTotal))
                    gapPlaced = true
                }
                if (showMarker && !markerPlaced && c.index <= seekMark) {
                    add(markerRow(cues))
                    markerPlaced = true
                }
                add(
                    Row(
                        index = c.index,
                        start = c.start,
                        clock = PayloadFormatter.clock(c.start),
                        endClock = c.end.takeIf { it.isNotBlank() }?.let(PayloadFormatter::clock),
                        text = c.text,
                        live = false,
                        provisional = c.provisional,
                    ),
                )
            }
            // Semua cue yang masih ada sudah melewati batas (mis. daftar
            // baru berisi cue setelah lompatan): garis tetap ditampilkan di
            // bawah supaya jumlah cue baru tidak hilang.
            if (showMarker && !markerPlaced) add(markerRow(cues))
            // Kalau semua cue sesudah celah masih belum bernomor, baris
            // celah diletakkan di ujung bawah daftar.
            if (showGap && !gapPlaced) {
                add(gapRow(firstHole, provisionalTotal))
            }
        }
        if (next == rows) return

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = rows.size
            override fun getNewListSize(): Int = next.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val a = rows[oldPos]
                val b = next[newPos]
                if (a.marker || b.marker) return a.marker == b.marker
                if (a.gap || b.gap) return a.gap == b.gap
                // Nomor bisa bergeser setelah celah terisi; waktu mulai
                // adalah identitas yang stabil untuk baris cue.
                return a.start == b.start
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                rows[oldPos] == next[newPos]
        })

        rows.clear()
        rows.addAll(next)
        diff.dispatchUpdatesTo(this)
    }

    /** Baris celah: ketuk untuk memundurkan video ke awal celah. */
    private fun gapRow(hole: VtrPayload.Hole, count: Int): Row {
        val from = PayloadFormatter.clock(hole.from)
        val to = PayloadFormatter.clock(hole.to)
        return Row(
            index = -1,
            start = "",
            clock = "",
            endClock = null,
            text = context.getString(R.string.transcript_gap_mark, from, to, count),
            live = false,
            gap = true,
            gapStart = PayloadFormatter.parseTime(hole.from),
        )
    }

    /** Baris pemisah: cue di atasnya terekam setelah lompatan terakhir. */
    private fun markerRow(cues: List<VtrPayload.Cue>): Row {
        val clock = seekClock ?: "--:--"
        val baru = cues.count { it.index > seekMark }
        val text = if (baru > 0) {
            context.getString(R.string.transcript_seek_mark, baru, clock)
        } else {
            context.getString(R.string.transcript_seek_none, clock)
        }
        return Row(
            index = -1,
            start = "",
            clock = "",
            endClock = null,
            text = text,
            live = false,
            marker = true,
        )
    }

    fun clear() {
        if (rows.isEmpty()) return
        val count = rows.size
        rows.clear()
        notifyItemRangeRemoved(0, count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemTranscriptBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    inner class Holder(
        private val binding: ItemTranscriptBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            val context = binding.root.context

            // Holder dipakai ulang. Bersihkan dekorasi teks milik baris
            // sebelumnya supaya pill penanda tidak bocor ke cue biasa.
            binding.cueText.background = null
            binding.cueText.setPadding(0, 0, 0, 0)

            if (row.gap) {
                binding.cueIndex.visibility = View.GONE
                binding.cueTime.visibility = View.GONE
                binding.cueText.text = row.text
                binding.cueText.setTextColor(
                    ContextCompat.getColor(context, R.color.provisional_mark),
                )
                binding.root.background = ContextCompat.getDrawable(
                    context,
                    R.drawable.bg_gap_row,
                )
                binding.root.isClickable = true
                binding.root.isFocusable = true
                binding.root.setOnClickListener { onFillHole(row.gapStart) }
                return
            }

            if (row.marker) {
                binding.cueIndex.visibility = View.GONE
                binding.cueTime.visibility = View.GONE
                binding.cueText.text = "\u25B8 ${row.text}"
                binding.cueText.setTextColor(
                    ContextCompat.getColor(context, R.color.meta_text),
                )
                binding.cueText.background = ContextCompat.getDrawable(
                    context,
                    R.drawable.bg_marker_row,
                )
                val padH = (context.resources.displayMetrics.density * 10).toInt()
                val padV = (context.resources.displayMetrics.density * 5).toInt()
                binding.cueText.setPadding(padH, padV, padH, padV)
                binding.root.background = ColorDrawable(
                    ContextCompat.getColor(context, R.color.surface),
                )
                binding.root.isClickable = false
                binding.root.isFocusable = false
                binding.root.setOnClickListener(null)
                return
            }

            binding.cueIndex.visibility = View.VISIBLE
            binding.cueTime.visibility = View.VISIBLE
            binding.root.isClickable = true
            binding.root.isFocusable = true

            val accent = ContextCompat.getColor(context, R.color.panel_accent)
            val meta = ContextCompat.getColor(context, R.color.meta_text)
            val cueNumber = ContextCompat.getColor(context, R.color.cue_number)
            val provisionalMark =
                ContextCompat.getColor(context, R.color.provisional_mark)

            // Kolom nomor sekaligus menjadi penanda status. Ikon live
            // sengaja lebih besar daripada angka supaya cepat terbaca.
            val isLive = row.live && !row.replay
            val replayProvisional = row.replay && row.provisional
            val showMark = row.replay || row.provisional
            val showNumber = !row.provisional

            binding.cueLiveBadge.visibility = if (isLive) View.VISIBLE else View.GONE
            binding.cueMark.visibility = if (showMark) View.VISIBLE else View.GONE
            binding.cueNumber.visibility = if (showNumber) View.VISIBLE else View.GONE

            // Cue ulang yang masih di belakang celah tidak boleh memakai
            // nomor lamanya. Tampilkan ↺? supaya status ulang dan status
            // belum bernomor tetap terbaca bersamaan.
            binding.cueMark.text = when {
                replayProvisional -> "\u21BA?"
                row.replay -> "\u21BA"
                else -> "?"
            }
            binding.cueMark.setTextColor(
                if (row.replay && !row.provisional) cueNumber else provisionalMark,
            )
            binding.cueMark.contentDescription = when {
                replayProvisional ->
                    context.getString(R.string.transcript_replay_provisional_mark)
                row.replay -> context.getString(R.string.transcript_replay_mark)
                row.provisional -> context.getString(R.string.transcript_provisional_mark)
                else -> null
            }

            binding.cueNumber.text = row.index.toString()
            binding.cueNumber.setTextColor(if (isLive) accent else cueNumber)

            // Dua baris supaya kolom waktu tidak memakan lebar teks cue.
            binding.cueTime.text = if (row.endClock == null) {
                "${row.clock}\n\u2192 \u2026"
            } else {
                "${row.clock}\n\u2192 ${row.endClock}"
            }
            binding.cueText.text = row.text

            binding.cueText.setTextColor(
                when {
                    row.replay -> cueNumber
                    row.live -> accent
                    else -> ContextCompat.getColor(context, R.color.panel_text)
                },
            )
            binding.cueTime.setTextColor(
                when {
                    row.replay -> cueNumber
                    row.live -> accent
                    else -> meta
                },
            )

            binding.root.background = when {
                row.replay -> ContextCompat.getDrawable(context, R.drawable.bg_replay_row)
                row.live -> ContextCompat.getDrawable(context, R.drawable.bg_live_row)
                else -> null
            }

            binding.root.setOnClickListener {
                onSeek(PayloadFormatter.parseTime(row.start))
            }
        }
    }
}
