package dev.ifan.viusub

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
 * pertama, dekat dengan video, dan diberi latar cyan tipis plus awalan
 * panah supaya jelas mana yang sedang tampil.
 *
 * Cue yang sedang diputar ulang juga tampil di puncak, tetapi memakai
 * nomor, waktu mulai, dan waktu selesai cue lama. Baris lamanya tidak
 * ditampilkan dua kali selama mode ulang aktif.
 */
class TranscriptAdapter(
    private val context: Context,
    private val onSeek: (Double) -> Unit,
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
    ) {
        // Garis pemisah hanya berguna selama cue lama belum terlewati.
        // Setelah posisi video melewati cue lama terakhir, semua cue di
        // depan adalah rekaman baru, jadi garisnya dihilangkan.
        val boundaryCue = cues.lastOrNull { it.index <= seekMark }
        val boundaryEnd = boundaryCue?.end?.let(PayloadFormatter::parseTime) ?: 0.0
        val showMarker = seekMark > 0 &&
            !(boundaryEnd > 0 && position >= boundaryEnd)

        // Cue ulang memakai nomor cue lama. Jangan tampilkan cue lama itu
        // dua kali: sekali sebagai baris live di puncak, sekali di posisi
        // aslinya di bawah.
        val replayIndices = pending.filter { it.replay }.mapTo(HashSet()) { it.index }
        val shownReplay = HashSet<Int>()

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
                    ),
                )
            }
            var markerPlaced = false
            for (c in cues.asReversed()) {
                if (c.index in replayIndices) continue
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
                    ),
                )
            }
            // Semua cue yang masih ada sudah melewati batas (mis. daftar
            // baru berisi cue setelah lompatan): garis tetap ditampilkan di
            // bawah supaya jumlah cue baru tidak hilang.
            if (showMarker && !markerPlaced) add(markerRow(cues))
        }
        if (next == rows) return

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = rows.size
            override fun getNewListSize(): Int = next.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                rows[oldPos].index == next[newPos].index

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                rows[oldPos] == next[newPos]
        })

        rows.clear()
        rows.addAll(next)
        diff.dispatchUpdatesTo(this)
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

        /** Ripple bawaan baris, dipakai lagi saat cue tidak live. */
        private val normalBackground: Drawable? = binding.root.background

        fun bind(row: Row) {
            val context = binding.root.context

            if (row.marker) {
                binding.cueIndex.visibility = View.GONE
                binding.cueTime.visibility = View.GONE
                binding.cueText.text = "\u25B8 ${row.text}"
                binding.cueText.setTextColor(
                    ContextCompat.getColor(context, R.color.meta_text),
                )
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

            binding.cueIndex.text = row.index.toString()
            binding.cueTime.text = if (row.endClock == null) {
                "${row.clock} \u2192 \u2026"
            } else {
                "${row.clock} \u2192 ${row.endClock}"
            }
            binding.cueText.text = when {
                row.replay -> "\u21BA ${row.text}"
                row.live -> "\u25B8 ${row.text}"
                else -> row.text
            }

            binding.cueText.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (row.live) R.color.panel_accent else R.color.panel_text,
                ),
            )
            binding.cueIndex.setTextColor(if (row.live) accent else cueNumber)
            binding.cueTime.setTextColor(if (row.live) accent else meta)

            binding.root.background =
                if (row.live) {
                    ColorDrawable(ContextCompat.getColor(context, R.color.live_row))
                } else {
                    normalBackground
                }

            binding.root.setOnClickListener {
                onSeek(PayloadFormatter.parseTime(row.start))
            }
        }
    }
}
