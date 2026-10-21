package dev.ifan.viusub

import android.os.Handler
import android.webkit.WebView
import org.json.JSONObject
import kotlin.math.abs

/**
 * Satu pintu untuk semua lompatan dari transkrip ke pemutar.
 *
 * Lapisan ini tidak menggambar UI apa pun. Tugasnya:
 *
 *  - menjepit target dan mengirim satu permintaan pada satu waktu;
 *  - menyimpan tap terbaru saja kalau pengguna menekan beberapa baris
 *    beruntun, jadi tidak ada pesan "pemutar masih sibuk";
 *  - memasang ulang skrip halaman kalau belum siap;
 *  - menunggu video benar-benar bergerak, bukan hanya menerima event
 *    `seeked`;
 *  - meminta halaman memutar video lagi kalau lompatan mendarat saat
 *    pemutar sedang dijeda.
 *
 * Semua pesan, penanda transkrip, dan status untuk panel kecil diserahkan
 * lewat callback supaya controller ini tidak bergantung pada Activity.
 */
class PlaybackJumpController(
    private val webView: WebView,
    private val handler: Handler,
    private val installScripts: () -> Unit,
    private val onJumped: (target: Double, cueCount: Int) -> Unit,
    private val onFailed: (reason: String) -> Unit,
    private val onState: (State) -> Unit = {},
) {
    enum class Phase {
        IDLE,
        SENDING,
        QUEUED,
        BUFFERING,
        RECOVERING,
        RETRYING,
        PAUSED,
        SUCCESS,
        FAILED,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val target: Double = 0.0,
        val gap: Boolean = false,
        val attempt: Int = 0,
        val reason: String = "",
    )

    private data class Request(val target: Double, val gap: Boolean)

    private var active: Request? = null
    private var queued: Request? = null
    private var lastRequest: Request? = null
    private var lastState = State()
    private var verifying = false
    private var phaseAttempts = 0
    private var verifyAttempts = 0
    private var verifyReseeks = 0
    private var recoveries = 0
    private var bufferStalls = 0
    private var acceptedTarget = 0.0
    private var acceptedCueCount = 0
    private var lastPosition = -1.0
    private var moved = false
    private var destroyed = false

    /**
     * Minta lompatan. Kalau masih ada lompatan yang sedang diverifikasi,
     * permintaan baru langsung menggantikannya. Kalau lompatan masih
     * dikirim ke halaman, hanya permintaan terbaru yang disimpan.
     *
     * [gap] hanya memengaruhi tampilan status; target sudah dihitung
     * pemanggil (untuk celah: satu detik sebelum awal celah).
     */
    fun jumpTo(seconds: Double, gap: Boolean = false) {
        if (destroyed) return
        val request = Request(seconds.coerceAtLeast(0.0), gap)
        if (active != null) {
            if (verifying) {
                // Seek sebelumnya sudah diterima halaman. Jangan menunggu
                // verifikasinya selesai; tap terbaru yang menang.
                active = null
                queued = null
                verifying = false
                handler.removeCallbacks(verify)
                handler.removeCallbacks(retry)
                start(request)
            } else {
                queued = request
                emit(State(Phase.QUEUED, request.target, request.gap))
            }
            return
        }
        start(request)
    }

    /** Coba lagi lompatan terakhir setelah status gagal. */
    fun retry() {
        if (destroyed || active != null) return
        val request = lastRequest ?: return
        start(request)
    }

    /** Tombol Putar saat autoplay ditahan browser atau video beku. */
    fun playNow() {
        if (destroyed) return
        val request = active ?: return
        handler.removeCallbacks(verify)
        if (!verifying) {
            // Verification sempat berhenti karena status PAUSED. Mulai
            // lagi supaya tombol Putar juga memulihkan pemeriksaan
            // pergerakan, bukan sekadar memanggil play() sekali.
            verifying = true
            verifyAttempts = 0
            lastPosition = -1.0
            moved = false
        }
        webView.evaluateJavascript(RecorderScript.JUMP_PLAY, null)
        emit(State(Phase.BUFFERING, acceptedTarget, request.gap))
        handler.postDelayed(verify, VERIFY_DELAY_MS)
    }

    /** Dipakai saat halaman mulai dimuat ulang atau pengguna membatalkan. */
    fun cancel() {
        cancelInternal(notify = true)
    }

    /** Activity selesai; tidak boleh ada callback yang tersisa. */
    fun destroy() {
        destroyed = true
        cancelInternal(notify = false)
    }

    private fun cancelInternal(notify: Boolean) {
        active = null
        queued = null
        lastRequest = null
        verifying = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(verify)
        if (notify) emit(State())
    }

    private fun start(request: Request) {
        active = request
        lastRequest = request
        verifying = false
        phaseAttempts = 0
        verifyAttempts = 0
        verifyReseeks = 0
        recoveries = 0
        acceptedTarget = request.target
        acceptedCueCount = 0
        lastPosition = -1.0
        moved = false
        emit(State(Phase.SENDING, request.target, request.gap))
        send(request)
    }

    private fun send(request: Request) {
        if (active !== request || destroyed) return
        webView.evaluateJavascript(RecorderScript.jump(request.target)) { raw ->
            if (active !== request || destroyed) return@evaluateJavascript
            val result = parse(raw)
            if (result?.optBoolean("ok") == true) {
                acceptedTarget = result.optDouble("target", request.target)
                acceptedCueCount = result.optInt("cueCount", 0)
                verifying = true
                verifyAttempts = 0
                lastPosition = -1.0
                moved = false
                emit(State(Phase.BUFFERING, acceptedTarget, request.gap))
                handler.postDelayed(verify, VERIFY_DELAY_MS)
                return@evaluateJavascript
            }
            val reason = result?.optString("reason").orEmpty()
            when (reason) {
                "busy" -> retryOrFail(request, BUSY_LIMIT)
                "no-script" -> {
                    installScripts()
                    retryOrFail(request, SCRIPT_LIMIT)
                }
                "no-video" -> retryOrFail(request, VIDEO_LIMIT, "failed")
                "seek-error" -> retryOrFail(request, SEEK_LIMIT)
                else -> {
                    installScripts()
                    retryOrFail(request, SCRIPT_LIMIT)
                }
            }
        }
    }

    private fun retryOrFail(
        request: Request,
        limit: Int,
        exhaustedReason: String = "timeout",
    ) {
        if (active !== request || destroyed) return
        phaseAttempts++
        if (phaseAttempts > limit) {
            fail(exhaustedReason)
            return
        }
        // Tap yang lebih baru tidak perlu menunggu request lama sampai
        // batas penuh, tetapi tetap beri satu kesempatan agar seek pertama
        // selesai lebih dulu.
        if (queued != null && phaseAttempts >= 3) {
            finishAndStartQueued()
            return
        }
        emit(State(Phase.RETRYING, request.target, request.gap, phaseAttempts))
        handler.postDelayed(retry, RETRY_DELAY_MS)
    }

    private val retry = Runnable {
        active?.let { send(it) }
    }

    private val verify: Runnable = Runnable {
        val request = active ?: return@Runnable
        webView.evaluateJavascript(RecorderScript.JUMP_STATE) { raw ->
            if (active !== request || destroyed) return@evaluateJavascript
            val state = parse(raw)
            if (state?.optBoolean("ok") != true) {
                verifyAttempts++
                if (verifyAttempts >= VERIFY_LIMIT) {
                    fail("timeout")
                } else {
                    handler.postDelayed(verify, VERIFY_DELAY_MS)
                }
                return@evaluateJavascript
            }

            // Pause manual membatalkan seluruh verifikasi. Jangan pernah
            // mengirim JUMP_PLAY lagi setelah pengguna sengaja berhenti.
            if (state.optBoolean("userPaused", false)) {
                cancelInternal(notify = true)
                return@evaluateJavascript
            }

            val position = state.optDouble("position", -1.0)
            val paused = state.optBoolean("paused", true)
            val seeking = state.optBoolean("seeking", true)
            val ended = state.optBoolean("ended", false)
            val ready = state.optInt("readyState", 0)
            val buffered = state.optDouble("buffered", 0.0)
            // readyState 1–2 berarti data belum cukup; jangan menggeser
            // currentTime karena justru memicu pemuatan ulang segmen.
            val lowData = ready in 1..2 && buffered < 0.5
            val near = isNear(position, ended)

            // Perpindahan dari posisi lama ke target adalah seek, bukan
            // bukti video berjalan. Baru hitung sebagai gerak kalau posisi
            // sebelumnya juga sudah dekat target.
            val wasNear = isNear(lastPosition, ended)
            if (near && lastPosition >= 0 && wasNear && position > lastPosition + 0.02) {
                moved = true
            }
            lastPosition = position

            // Tunggu sampai seek benar-benar selesai supaya posisi akhir
            // tidak berubah lagi setelah controller melaporkan sukses.
            if (near && moved && !seeking) {
                succeed()
                return@evaluateJavascript
            }

            // Hanya minta putar kalau posisi sudah dekat target. Kalau seek
            // belum mendarat, memutar video di posisi lama justru membuat
            // verification melihat pergerakan yang salah.
            if (near && paused && !ended) {
                webView.evaluateJavascript(RecorderScript.JUMP_PLAY, null)
                if (verifyAttempts >= 2) {
                    emit(State(Phase.PAUSED, acceptedTarget, request.gap))
                }
            }

            // Video bisa mengaku sedang berjalan (paused=false) tetapi
            // frame-nya berhenti, atau seek-nya macet. Pause/play + dorongan
            // currentTime di halaman biasanya membangunkan decoder. Jangan
            // lakukan saat data memang belum cukup (lowData), kecuali seek
            // sudah terlalu lama macet — saat itu seek lama harus dibatalkan.
            val frozen = near && !moved && !paused && !ended
            val stuckSeek = near && !moved && seeking && verifyAttempts >= 5
            if (near && !moved && !ended && recoveries < 4 &&
                verifyAttempts >= 3 && (stuckSeek || (frozen && !lowData))) {
                recoveries++
                webView.evaluateJavascript(RecorderScript.RECOVER, null)
                lastPosition = -1.0
                moved = false
                verifyAttempts = 0
                emit(State(Phase.RECOVERING, acceptedTarget, request.gap))
                handler.postDelayed(verify, VERIFY_DELAY_MS)
                return@evaluateJavascript
            }

            verifyAttempts++
            if (verifyAttempts >= VERIFY_LIMIT) {
                if (near && (moved || ended) && !seeking) {
                    succeed()
                } else if (near && paused && !ended) {
                    // Autoplay ditahan. Jangan tampilkan error; biarkan
                    // pengguna menekan Putar dan verification dimulai lagi.
                    verifying = false
                    emit(State(Phase.PAUSED, acceptedTarget, request.gap))
                } else if (near && lowData && bufferStalls < 2) {
                    // Buffer jaringan lambat bukan decoder beku. Beri
                    // waktu tambahan sebelum menyimpulkan gagal.
                    bufferStalls++
                    verifyAttempts = 0
                    emit(State(Phase.BUFFERING, acceptedTarget, request.gap))
                    handler.postDelayed(verify, VERIFY_DELAY_MS)
                    return@evaluateJavascript
                } else if (near) {
                    // Sudah di posisi target, tetapi video tetap beku
                    // setelah empat kali pemulihan.
                    fail("stalled")
                } else if (verifyReseeks < 1) {
                    // Bitmovin kadang mengabaikan currentTime pertama,
                    // terutama untuk lompatan mundur ke celah. Ulangi
                    // sekali sebelum menyerah.
                    verifyReseeks++
                    phaseAttempts = 0
                    lastPosition = -1.0
                    moved = false
                    emit(State(Phase.BUFFERING, request.target, request.gap))
                    send(request)
                } else {
                    fail("timeout")
                }
                return@evaluateJavascript
            }

            handler.postDelayed(verify, VERIFY_DELAY_MS)
        }
    }

    private fun isNear(position: Double, ended: Boolean): Boolean {
        if (position < 0) return false
        if (ended) return true
        // Target 0 detik sering mendarat di 0–0,5 karena jalur Home Bitmovin.
        val tolerance = if (acceptedTarget < 1.0) 0.9 else 1.5
        return abs(position - acceptedTarget) <= tolerance
    }

    private fun succeed() {
        handler.removeCallbacks(verify)
        handler.removeCallbacks(retry)
        verifying = false
        val request = active
        val target = acceptedTarget
        val cueCount = acceptedCueCount
        if (request != null) {
            emit(State(Phase.SUCCESS, target, request.gap))
        }
        onJumped(target, cueCount)
        finishAndStartQueued()
    }

    private fun fail(reason: String) {
        handler.removeCallbacks(verify)
        handler.removeCallbacks(retry)
        verifying = false
        if (queued != null) {
            finishAndStartQueued()
            return
        }
        val request = active
        emit(
            State(
                phase = Phase.FAILED,
                target = request?.target ?: acceptedTarget,
                gap = request?.gap == true,
                reason = reason,
            ),
        )
        onFailed(reason)
        active = null
    }

    private fun finishAndStartQueued() {
        handler.removeCallbacks(verify)
        handler.removeCallbacks(retry)
        active = null
        verifying = false
        val next = queued
        queued = null
        if (next != null) start(next)
    }

    private fun emit(next: State) {
        if (next == lastState) return
        lastState = next
        onState(next)
    }

    private fun parse(raw: String?): JSONObject? = try {
        val json = raw?.let { unquote(it) } ?: return null
        JSONObject(json)
    } catch (e: Exception) {
        null
    }

    private fun unquote(raw: String): String? = try {
        JSONObject("{\"v\":$raw}").optString("v").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val RETRY_DELAY_MS = 350L
        const val VERIFY_DELAY_MS = 280L
        const val BUSY_LIMIT = 20
        const val SCRIPT_LIMIT = 4
        const val VIDEO_LIMIT = 10
        const val SEEK_LIMIT = 2
        const val VERIFY_LIMIT = 24
    }
}
