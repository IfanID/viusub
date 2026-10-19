package dev.ifan.viusub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Menulis file hasil ekspor ke folder yang dipilih pengguna.
 *
 * Memakai Storage Access Framework, bukan permission storage. Pengguna
 * memilih folder sekali, izin baca+tulis-nya disimpan permanen oleh sistem,
 * lalu semua file berikutnya ditulis dari sana tanpa minta lagi.
 *
 * Dipakai hanya untuk ekspor. Perekaman subtitle sendiri tetap sepenuhnya
 * di dalam WebView dan tidak menyentuh storage sampai tombol ditekan.
 */
object ExportStore {

    private const val TAG = "ExportStore"

    private const val PREF = "viu_sub_export"
    private const val KEY_TREE_URI = "tree_uri"

    /** Flag yang harus disimpan agar folder tetap bisa ditulis setelah restart. */
    val PERSIST_FLAGS: Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun rememberTree(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, PERSIST_FLAGS)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
        true
    } catch (e: Exception) {
        Log.e(TAG, "Gagal menyimpan izin folder", e)
        false
    }

    fun storedTree(context: Context): Uri? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?.let { Uri.parse(it) }

    /**
     * Menulis [text] ke [fileName] di dalam folder yang sudah dipilih.
     * Mengembalikan null kalau berhasil, atau pesan error kalau gagal.
     */
    fun write(
        context: Context,
        treeUri: Uri,
        fileName: String,
        mimeType: String,
        text: String,
    ): String? {
        val resolver = context.contentResolver
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val doc = DocumentsContract.createDocument(resolver, parent, mimeType, fileName)
                ?: return "Gagal membuat file '$fileName'"

            resolver.openOutputStream(doc)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return "Gagal membuka file '$fileName' untuk ditulis"

            null
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menulis '$fileName'", e)
            e.message ?: "Gagal menulis '$fileName'"
        }
    }

    fun writeJson(context: Context, treeUri: Uri, fileName: String, json: String): String? =
        write(context, treeUri, fileName, "application/json", json)

    fun writeSrt(context: Context, treeUri: Uri, fileName: String, srt: String): String? =
        write(context, treeUri, fileName, "application/x-subrip", srt)
}