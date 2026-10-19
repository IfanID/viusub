package dev.ifan.viusub

/**
 * WebView menandai dirinya lewat beberapa tanda di User-Agent. Situs yang
 * mendeteksi WebView sering memblokir akses, jadi tandanya dibersihkan
 * supaya yang terlihat adalah Chrome biasa.
 *
 * Dua tanda yang dibuang di sini:
 *  - "wv" di dalam kurung platform, contoh:
 *      "... Android 14; Pixel 7 Build/UP1A.231005.007; wv) ..."
 *  - "Version/4.0" sebelum "Chrome/...", yang hanya ada di UA WebView:
 *      "... AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120..."
 *
 * Yang TIDAK ikut dibersihkan: header "X-Requested-With" yang berisi nama
 * package aplikasi. Menghapusnya butuh reflection ke API internal WebView,
 * yang rapuh dan bisa bikin crash di Android baru. Kalau sebuah situs
 * memblokir berdasarkan header itu, bukan User-Agent, solusinya bukan
 * app ini tapi Chrome Custom Tabs.
 */
object ChromeUserAgent {

    private val WV_MARKER = Regex("""\s*;\s*wv(?=\))""")
    private val LEGACY_VERSION = Regex("""\s*Version/4\.0(?=\s)""")

    /**
     * Mengubah User-Agent default WebView menjadi bentuk Chrome biasa.
     * Kalau string input tidak mengandung tanda di atas, dikembalikan
     * apa adanya.
     */
    fun from(defaultUserAgent: String): String =
        LEGACY_VERSION.replace(WV_MARKER.replace(defaultUserAgent, ""), "")
}