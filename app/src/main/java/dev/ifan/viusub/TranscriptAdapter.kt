package dev.ifan.viusub

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
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
 */
class TranscriptAdapter(
    private val onSeek: (Double) -> Unit,
) : RecyclerView.Adapter<TranscriptAdapter.Holder>() {

    data class Row(
        val index: Int,
        val start: String,
        val clock: String,
        val endClock: String?,
        val text: String,
        val live: Boolean,
    )

    private val rows = mutableListOf<Row>()

    /** Mengganti isi daftar tanpa kehilangan posisi gulir kalau tidak berubah. */
    fun submit(cues: List<VtrPayload.Cue>, pending: List<VtrPayload.Cue>) {
        val next = buildList {
            // Urutan terbalik: yang paling baru di atas, yang lama di bawah.
            // Cue live selalu berada di puncak supaya dekat dengan video.
            for (c in pending) {
                add(
                    Row(
                        index = c.index,
                        start = c.start,
                        clock = PayloadFormatter.clock(c.start),
                        endClock = null,
                        text = c.text,
                        live = true,
                    ),
                )
            }
            for (c in cues.asReversed()) {
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
            val accent = ContextCompat.getColor(context, R.color.panel_accent)
            val meta = ContextCompat.getColor(context, R.color.meta_text)
            val cueNumber = ContextCompat.getColor(context, R.color.cue_number)

            binding.cueIndex.text = row.index.toString()
            binding.cueTime.text = if (row.endClock == null) {
                "${row.clock} \u2192 \u2026"
            } else {
                "${row.clock} \u2192 ${row.endClock}"
            }
            binding.cueText.text =
                if (row.live) "\u25B8 ${row.text}" else row.text

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
