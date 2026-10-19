package dev.ifan.viusub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pemuat thumbnail sederhana.
 *
 * Sengaja tidak memakai library: satu-satunya kebutuhan di sini adalah
 * mengambil satu gambar per baris dan menyimpannya sebentar di memori.
 * Menambah dependensi untuk itu tidak sepadan, apalagi build di perangkat
 * ini harus mengunduh semuanya dari awal.
 *
 * Gambar Viu menolak permintaan tanpa Referer, jadi header itu ikut dikirim.
 */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Dipakai untuk membatalkan pemuatan lama saat baris didaur ulang. */
    private val tags = HashMap<ImageView, String>()

    fun load(view: ImageView, address: String) {
        view.setImageDrawable(null)

        if (address.isBlank()) return

        cache.get(address)?.let {
            view.setImageBitmap(it)
            return
        }

        tags[view] = address

        pool.execute {
            val bitmap = try {
                download(address)
            } catch (e: Exception) {
                null
            }

            main.post {
                // Baris bisa sudah dipakai untuk episode lain; jangan pasang
                // gambar yang salah.
                if (tags[view] == address) {
                    if (bitmap != null) {
                        cache.put(address, bitmap)
                        view.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun download(address: String): Bitmap? {
        val connection = URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Referer", "https://www.viu.com/")
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val bytes = connection.inputStream.use { it.readBytes() }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            options.inSampleSize = sampleSize(options.outWidth, options.outHeight)
            options.inJustDecodeBounds = false

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }

    /** Menurunkan resolusi supaya memori tidak habis untuk gambar kecil. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 480 || height / sample > 480) sample *= 2
        return sample
    }
}