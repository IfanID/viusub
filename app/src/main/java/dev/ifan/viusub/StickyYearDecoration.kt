package dev.ifan.viusub

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Header tahun yang menempel di atas saat daftar digulir.
 *
 * Digambar di atas daftar, bukan bagian dari item, jadi baris yang sedang
 * lewat tetap terlihat di bawahnya. Saat header tahun berikutnya mendekat,
 * header yang menempel ikut terdorong ke atas — perilaku yang sama dengan
 * daftar berkontak di aplikasi pesan.
 *
 * Header hanya digambar kalau baris pertama yang terlihat bukan header itu
 * sendiri; kalau digambar terus, akan ada dua teks tahun bertumpuk.
 */
class StickyYearDecoration(
    private val adapter: EpisodeAdapter,
) : RecyclerView.ItemDecoration() {

    private var header: TextView? = null
    private var boundYear: Int? = null
    private var headerHeight = 0

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val manager = parent.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return

        // Baris pertama sudah berupa header tahun: tidak perlu menempelkan
        // apa pun, header aslinya sudah di posisi yang benar.
        if (adapter.isHeader(first)) return

        val year = adapter.yearAt(first) ?: return

        val view = header ?: run {
            val inflated = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_year, parent, false) as TextView
            header = inflated
            inflated
        }

        if (boundYear != year) {
            view.text = year.toString()
            boundYear = year

            val width = parent.width - parent.paddingStart - parent.paddingEnd
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            headerHeight = view.measuredHeight
        }

        // Dorong header ke atas saat header berikutnya hampir menyentuh,
        // supaya perpindahan tahun terasa menyatu, bukan melompat.
        var offset = 0f
        val next = adapter.nextHeaderPosition(first)
        if (next != RecyclerView.NO_POSITION) {
            val nextView = parent.findViewHolderForAdapterPosition(next)?.itemView
            if (nextView != null && nextView.top < headerHeight) {
                offset = (nextView.top - headerHeight).toFloat()
            }
        }

        canvas.save()
        canvas.translate(parent.paddingStart.toFloat(), parent.paddingTop + offset)
        view.draw(canvas)
        canvas.restore()
    }
}