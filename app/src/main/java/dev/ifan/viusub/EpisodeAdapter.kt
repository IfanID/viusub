package dev.ifan.viusub

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter Home.
 *
 * Dua mode tampilan:
 *  - tanpa kueri: dikelompokkan per tahun, nomor terbesar di atas;
 *  - dengan kueri: satu daftar datar, diurutkan menurut skor pencarian.
 *
 * Pengelompokan tahun sengaja hilang saat mencari: hasil pencarian sudah
 * punya urutannya sendiri berdasarkan relevansi, dan judul tahun di
 * tengah-tengah justru memutus urutan itu.
 */
class EpisodeAdapter(
    private val onPick: (Episode) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Year(val year: Int) : Row
        data class Item(val episode: Episode, val sorot: String) : Row
    }

    private val rows = mutableListOf<Row>()

    /** Kueri aktif, hanya dipakai untuk menyorot teks yang cocok. */
    private var query: String = ""

    /** Mode daftar: dikelompokkan per tahun, urut menurun. */
    fun submit(episodes: List<Episode>) {
        query = ""
        rows.clear()

        val byYear = episodes.groupBy { it.year }
            .toSortedMap(compareByDescending { it })

        for ((year, list) in byYear) {
            rows += Row.Year(year)
            list.sortedByDescending { it.number }
                .forEach { rows += Row.Item(it, "") }
        }

        notifyDataSetChanged()
    }

    /** Mode pencarian: datar, sudah terurut menurut skor. */
    fun submitSearch(episodes: List<Episode>, query: String) {
        this.query = query
        rows.clear()
        episodes.forEach { rows += Row.Item(it, query) }
        notifyDataSetChanged()
    }

    fun count(): Int = rows.count { it is Row.Item }

    /** Apakah baris ini header tahun, bukan episode. */
    fun isHeader(position: Int): Boolean =
        rows.getOrNull(position) is Row.Year

    /** Tahun yang berlaku untuk baris ini: header terdekat di atasnya. */
    fun yearAt(position: Int): Int? {
        for (i in position.coerceAtMost(rows.lastIndex) downTo 0) {
            val row = rows.getOrNull(i) ?: continue
            if (row is Row.Year) return row.year
        }
        return null
    }

    /** Posisi header tahun berikutnya, atau NO_POSITION kalau tidak ada. */
    fun nextHeaderPosition(position: Int): Int {
        for (i in position + 1 until rows.size) {
            if (rows[i] is Row.Year) return i
        }
        return RecyclerView.NO_POSITION
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Year) TYPE_YEAR else TYPE_EPISODE

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_YEAR) {
            YearHolder(inflater.inflate(R.layout.item_year, parent, false))
        } else {
            EpisodeHolder(inflater.inflate(R.layout.item_episode, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Year -> (holder as YearHolder).bind(row.year)
            is Row.Item -> (holder as EpisodeHolder).bind(row.episode, row.sorot)
        }
    }

    private class YearHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.yearLabel)
        fun bind(year: Int) {
            label.text = year.toString()
        }
    }

    private inner class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)

        fun bind(episode: Episode, sorot: String) {
            title.text = if (sorot.isBlank()) episode.title else highlight(episode.title, sorot)

            // Nomor episode diberi aksen supaya daftar bisa dipindai
            // cepat tanpa membaca seluruh baris meta.
            val metaText = buildString {
                append("EP ").append(episode.number)
                episode.duration().takeIf { it.isNotBlank() }?.let {
                    append("  ·  ").append(it)
                }
                episode.date.takeIf { it.isNotBlank() }?.let {
                    append("  ·  ").append(it)
                }
            }
            val numberEnd = 3 + episode.number.toString().length
            meta.text = SpannableString(metaText).apply {
                setSpan(
                    ForegroundColorSpan(HIGHLIGHT_TEXT),
                    0,
                    numberEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    numberEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            ThumbnailLoader.load(thumb, episode.coverUrl)

            itemView.setOnClickListener { onPick(episode) }
        }
    }

    /**
     * Menyorot potongan teks yang cocok dengan kueri.
     *
     * Pencocokan di sini memakai teks huruf kecil apa adanya, bukan bentuk
     * normalisasi. Akibatnya token yang hanya cocok setelah diakritik
     * dibuang (mis. "cafe" untuk "Café") tidak disorot — sama seperti
     * perilaku rout-web: teks tetap tampil utuh, hanya tanpa sorotan,
     * bukan dipaksa cocok di posisi yang salah.
     */
    private fun highlight(text: String, query: String): CharSequence {
        val tokens = Cari.siapkanKueri(query).token
        if (tokens.isEmpty()) return text

        val lower = text.lowercase()
        val spannable = SpannableString(text)
        var ada = false

        for (token in tokens) {
            var start = lower.indexOf(token)
            while (start >= 0) {
                // Hanya warna teks yang berubah; latar dibiarkan
                // transparan supaya tidak ada kotak di belakang kata.
                spannable.setSpan(
                    ForegroundColorSpan(HIGHLIGHT_TEXT),
                    start,
                    start + token.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                ada = true
                start = lower.indexOf(token, start + token.length)
            }
        }

        return if (ada) spannable else text
    }

    private companion object {
        const val TYPE_YEAR = 0
        const val TYPE_EPISODE = 1

        /** Cyan penuh untuk teks yang cocok. Latar tidak diwarnai. */
        val HIGHLIGHT_TEXT: Int = Color.rgb(0, 229, 255)
    }
}