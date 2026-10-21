package dev.ifan.viusub

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Header tahun yang menempel di tepi atas daftar.
 *
 * Berbeda dari ItemDecoration, header ini adalah View asli yang diletakkan
 * di atas RecyclerView. Posisinya selalu rata dengan tepi atas daftar,
 * sehingga baris episode tidak bisa menyelip di atasnya.
 *
 * Header disembunyikan saat header tahun asli masih terlihat, saat daftar
 * kosong, atau saat mode pencarian (hasil pencarian tidak punya header
 * tahun). Saat header tahun berikutnya mendekat, header ini didorong ke
 * atas supaya perpindahan tahun terasa menyatu.
 */
class StickyYearOverlay(
    private val list: RecyclerView,
    private val header: TextView,
    private val adapter: EpisodeAdapter,
) : RecyclerView.OnScrollListener() {

    private var boundYear: Int? = null

    init {
        list.addOnScrollListener(this)
        header.visibility = View.GONE
    }

    /** Dipanggil setelah data daftar berubah, saat layout belum tentu selesai. */
    fun updateAfterLayout() {
        list.post { update() }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        update()
    }

    private fun update() {
        val manager = list.layoutManager as? LinearLayoutManager
        val first = manager?.findFirstVisibleItemPosition()

        // Saat header tahun asli masih terlihat, overlay tidak perlu
        // ditampilkan: header asli sudah berada di posisi yang benar.
        if (first == null ||
            first == RecyclerView.NO_POSITION ||
            adapter.isHeader(first)
        ) {
            hide()
            return
        }

        val year = adapter.yearAt(first)
        if (year == null) {
            // Mode pencarian tidak punya header tahun.
            hide()
            return
        }

        if (boundYear != year) {
            header.text = year.toString()
            boundYear = year
        }
        header.visibility = View.VISIBLE

        // Tinggi belum diketahui tepat setelah visibility berubah. Tunggu
        // satu putaran layout, lalu hitung dorongan header berikutnya.
        if (header.height == 0) {
            header.post { update() }
            return
        }

        var offset = 0f
        val next = adapter.nextHeaderPosition(first)
        if (next != RecyclerView.NO_POSITION) {
            val nextView = list.findViewHolderForAdapterPosition(next)?.itemView
            if (nextView != null && nextView.top < header.height) {
                offset = (nextView.top - header.height).toFloat()
            }
        }
        header.translationY = offset
    }

    private fun hide() {
        header.visibility = View.GONE
        header.translationY = 0f
    }
}
