package dev.ifan.viusub

/**
 * Kode perekam subtitle, ditulis langsung di sourcecode app.
 *
 * Asalnya userscript Tampermonkey "Viu Subtitle Timing Recorder" v5.1. Yang
 * dibuang saat dipindahkan ke sini:
 *
 *  - header blok "==UserScript==" (metadata Tampermonkey, tidak berarti apa-apa)
 *  - guard window.__VTR__ (diganti window.ViuSubRecorder)
 *  - seluruh pembuatan panel/info/preview beserta tiga tombolnya
 *  - fungsi dl() beserta pembangkitan nama file .srt
 *  - pemanggilan renderPreview() di dalam tick()
 *
 * Yang dipertahankan persis: cara memindai DOM, dedupe, penggabungan baris,
 * perhitungan cakupan/lubang, dan penanganan pindah episode.
 *
 * Yang sengaja dibuang saat perubahan terakhir: seluruh penyimpanan
 * localStorage — autosave, restore, dan pemangkasan simpanan. Perekam
 * selalu mulai dari nol setiap episode dibuka, jadi rekaman hilang kalau
 * halaman dimuat ulang di tengah jalan.
 *
 * UI sekarang milik app, jadi skrip ini hanya mengumpulkan data dan
 * menyediakannya lewat window.ViuSubRecorder.
 *
 * JS ini wajib jalan di dalam halaman, bukan di Android: yang dipindai
 * adalah DOM viu.com (span subtitle dan posisinya), dan kode native tidak
 * bisa menyentuh DOM halaman web.
 */
object RecorderScript {

    /** Jumlah cue terakhir yang dikirim ke panel app. */
    private const val PREVIEW_MAX = 30

    /** Dipasang lewat evaluateJavascript setiap kali halaman selesai dimuat. */
    const val SOURCE = """
(function () {
  'use strict';
  if (window.ViuSubRecorder) return;

  var POLL_MS = 40;          // jeda minimum antar-scan (~25 scan/detik)

  var cues = [];
  var live = new Map();       // element -> { start, text, top }
  var subRoot = null;         // cache container subtitle
  var subRootMiss = 0;
  var statCache = { at: 0, gaps: [], covered: 0 };

  // Penghitung kenapa sebuah kandidat ditolak. Direset tiap scan, jadi
  // angkanya menggambarkan kondisi terkini. Dipakai untuk memastikan filter
  // mana yang membuang subtitle, bukan menebak.
  var rejects = {
    bmpui: 0, leaf: 0, empty: 0, long: 0,
    symbol: 0, timecode: 0, closest: 0, geometry: 0
  };
  var lastTick = 0;

  // Posisi video terakhir yang sempat terbaca. Dipakai untuk menutup cue
  // yang masih berjalan saat seek, karena pada saat itu currentTime sudah
  // melompat ke posisi baru.
  var lastTime = 0;
  var lastPath = location.pathname;

  function getVideo() {
    var vs = document.querySelectorAll('video');
    var best = null, bestArea = 0;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (!v.videoWidth) continue;
      var r = v.getBoundingClientRect();
      var area = r.width * r.height;
      var score = area * (v.paused ? 0.5 : 1);
      if (score > bestArea) { bestArea = score; best = v; }
    }
    if (best) return best;
    return vs.length ? vs[0] : null;
  }

  function collect() {
    var v = getVideo();
    if (!v || !v.videoWidth) return [];
    var r = v.getBoundingClientRect();
    if (!r.width || !r.height) return [];

    var scope = document;
    if (subRoot && subRoot.isConnected) scope = subRoot;

    var out = [];
    rejects = {
      bmpui: 0, leaf: 0, empty: 0, long: 0,
      symbol: 0, timecode: 0, closest: 0, geometry: 0
    };
    var all = scope.querySelectorAll('span,div,p,em,b,strong');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var cls = String(el.className || '');
      if (cls.indexOf('bmpui') !== -1) { rejects.bmpui++; continue; }
      if (el.firstElementChild) { rejects.leaf++; continue; }
      var t = (el.textContent || '').trim();
      // Batas 60 karakter membuang subtitle panjang tanpa jejak.
      // Terbukti dari data uji: teks terpanjang yang lolos tepat 60
      // karakter, dan 42 cue menumpuk di rentang 50-60. Nilai 180
      // masih jauh di bawah teks container, jadi filter lain tetap aman.
      if (!t || t.length > 180) { rejects.empty++; continue; }
      // Hanya buang kalau SELURUH teks terdiri dari simbol (ikon UI).
      // Versi sebelumnya membuang baris yang sekadar MENGANDUNG simbol,
      // sehingga baris lagu ber-notasi musik ikut hilang.
      if (/^[\u2190-\u21FF\u2600-\u27BF\uFE0F\s]+$/.test(t)) { rejects.symbol++; continue; }
      if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(t)) { rejects.timecode++; continue; }
      if (el.closest('[class*="quality"],[class*="Quality"],[class*="menu"],[class*="Menu"],[role="menu"],[class*="option"],[class*="caption"],[class*="episode"],[class*="Episode"],[class*="list"],[class*="List"]')) { rejects.closest++; continue; }

      var b = el.getBoundingClientRect();
      if (b.width < 30 || b.height < 8) { rejects.geometry++; continue; }
      if (b.height > 100) { rejects.geometry++; continue; }
      if (b.width > r.width * 0.95) { rejects.geometry++; continue; }
      if (b.top < r.top + r.height * 0.40) { rejects.geometry++; continue; }
      if (b.bottom > r.bottom - 2) { rejects.geometry++; continue; }
      if (b.bottom <= r.top || b.top >= r.bottom) { rejects.geometry++; continue; }

      out.push({ el: el, text: t, top: b.top });
    }
    out.sort(function (a, b) { return a.top - b.top; });

    if (out.length && !subRoot) {
      var p = out[0].el.parentElement;
      if (p && p !== document.body) subRoot = p;
      subRootMiss = 0;
    } else if (subRoot && !out.length) {
      if (++subRootMiss > 4) { subRoot = null; subRootMiss = 0; }
    } else if (out.length) {
      subRootMiss = 0;
    }

    return out;
  }

  function scan() {
    var v = getVideo();
    if (!v) return;
    var now = v.currentTime;
    if (!now) return;
    lastTime = now;

    var cands = collect();
    var present = new Set();

    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      present.add(c.el);

      var rec = live.get(c.el);
      if (!rec) {
        live.set(c.el, { start: now, text: c.text, top: c.top });
      } else if (rec.text !== c.text) {
        push(rec.start, now, rec.text, rec.top);
        live.set(c.el, { start: now, text: c.text, top: c.top });
      } else {
        rec.top = c.top;
      }
    }

    live.forEach(function (rec, el) {
      if (!present.has(el)) {
        push(rec.start, now, rec.text, rec.top);
        live.delete(el);
      }
    });
  }

  /**
   * Dipanggil saat seek. Cue yang sedang tampil HARUS didorong ke daftar
   * dulu sebelum referensinya dibuang.
   *
   * Sebelumnya fungsi ini hanya live.clear(), jadi subtitle yang sudah
   * terdeteksi dan tampil di panel lenyap tanpa jejak begitu timeline
   * digeser. End-nya memakai lastTime, bukan currentTime, karena pada
   * peristiwa seeking currentTime sudah melompat ke posisi tujuan.
   */
  function flushLive() {
    var end = lastTime;
    live.forEach(function (rec) {
      if (end > rec.start) push(rec.start, end, rec.text, rec.top);
    });
    live.clear();
  }

  function push(start, end, text, top) {
    if (end - start < 0.15) return;
    var last = cues[cues.length - 1];

    if (isDuplicate(text, start)) return;

    if (last) {
      var gap = start - last.end;
      var sameTime = Math.abs(start - last.start) < 0.4;

      if (last.text === text && gap < 0.15 && gap > -0.2) {
        last.end = Math.max(last.end, end);
        statCache.at = 0;
        return;
      }

      if (sameTime && gap < 0.35) {
        if (!last._parts) {
          last._parts = [{ t: last.text, y: (last._top || 0) }];
        }
        last._parts.push({ t: text, y: (top || 0) });
        last._parts.sort(function (a, b) { return a.y - b.y; });
        last.text = last._parts.map(function (p) { return p.t; }).join(' ');
        last.end = Math.max(last.end, end);
        statCache.at = 0;
        return;
      }
    }

    cues.push({ start: +start.toFixed(3), end: +end.toFixed(3), text: text, _top: top || 0 });
    statCache.at = 0;
  }

  function isDuplicate(text, start) {
    var from = Math.max(0, cues.length - 40);
    for (var i = cues.length - 1; i >= from; i--) {
      if (cues[i].text === text && Math.abs(cues[i].start - start) < 0.8) return true;
    }
    return false;
  }

  function ts(sec, sep) {
    function p(n, w) { n = String(n); while (n.length < (w || 2)) n = '0' + n; return n; }
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
    var s = Math.floor(sec % 60), ms = Math.round((sec - Math.floor(sec)) * 1000);
    return p(h) + ':' + p(m) + ':' + p(s) + sep + p(ms, 3);
  }

  function totalOf() {
    var v = getVideo();
    if (v && isFinite(v.duration) && v.duration > 0) return v.duration;
    var last = cues[cues.length - 1];
    return last ? last.end : 0;
  }

  function pendingCount() { return live.size; }

  function stats() {
    var now = Date.now();
    if (now - statCache.at < 1000) return statCache;
    statCache.at = now;

    var list = cues;
    var out = [];
    var prevEnd = 0;
    var max = 0;
    for (var i = 0; i < list.length; i++) {
      if (list[i].start - prevEnd > 3) {
        out.push({ from: prevEnd, to: list[i].start, len: list[i].start - prevEnd });
      }
      if (list[i].end > prevEnd) prevEnd = list[i].end;
      if (list[i].end > max) max = list[i].end;
    }
    statCache.gaps = out;
    statCache.covered = max;
    return statCache;
  }

  function gaps() { return stats().gaps; }
  function covered() { return stats().covered; }

  function payload() {
    var list = cues.slice().sort(function (a, b) { return a.start - b.start; });
    var total = totalOf();
    var g = getVideo();
    var pos = g ? g.currentTime : (list.length ? list[list.length - 1].end : 0);
    var remaining = Math.max(0, total - pos);
    var st = stats();
    var gapList = st.gaps;
    var cov = st.covered;
    return {
      source: location.href,
      // Judul halaman dipakai untuk nama file ekspor. Isinya biasanya
      // "Running Man (2024) Episode 733｜Variety Show Korea｜Viu Indonesia";
      // pemotongan dan pembersihan dilakukan di sisi Kotlin.
      title: (typeof document !== 'undefined' && document.title) ? document.title : '',
      method: 'DOM span observer',
      capturedAt: new Date().toISOString(),
      position: ts(pos, '.'),
      total: ts(Math.round(total), '.'),
      remaining: ts(Math.round(remaining), '.'),
      coverage: {
        covered: ts(cov, '.'),
        percent: total > 0 ? (Math.round((cov / total) * 1000) / 10).toFixed(1) : '0.0',
        gapCount: gapList.length,
        gaps: gapList.map(function (x) {
          return { from: ts(x.from, '.'), to: ts(x.to, '.'), seconds: Math.round(x.len) };
        })
      },
      cueCount: list.length,
      pending: pendingCount(),
      // Selalu nol: perekam tidak lagi memulihkan simpanan lama, jadi
      // tidak ada sesi sebelumnya yang bisa dilanjutkan.
      resumedFrom: 0,
      restoredCount: 0,
      filters: {
        bmpui: rejects.bmpui, leaf: rejects.leaf, empty: rejects.empty,
        long: rejects.long, symbol: rejects.symbol, timecode: rejects.timecode,
        closest: rejects.closest, geometry: rejects.geometry
      },
      pendingCues: (function () {
        var list2 = [];
        live.forEach(function (r) { list2.push(r); });
        list2.sort(function (a, b) { return a.start - b.start; });
        var lastCue = cues[cues.length - 1];
        if (lastCue) {
          list2 = list2.filter(function (p) {
            return !(p.text === lastCue.text && Math.abs(p.start - lastCue.start) < 0.4);
          });
        }
        return list2.map(function (p, n) {
          return { i: list.length + n + 1, start: ts(p.start, '.'), text: p.text };
        });
      })(),
      cues: list.map(function (c, i) {
        return {
          i: i + 1,
          start: ts(c.start, '.'),
          end: ts(c.end, '.'),
          duration: (Math.round((c.end - c.start) * 100) / 100).toFixed(2),
          text: c.text
        };
      })
    };
  }

  function checkRoute() {
    if (location.pathname === lastPath) return false;
    lastPath = location.pathname;
    cues.length = 0;
    live.clear();
    statCache.at = 0;
    statCache.gaps = [];
    statCache.covered = 0;
    return true;
  }

  function tick() {
    checkRoute();
    scan();
  }

  function loop(now) {
    if (now - lastTick >= POLL_MS) { lastTick = now; tick(); }
    requestAnimationFrame(loop);
  }

  document.addEventListener('seeking', flushLive, true);
  document.addEventListener('seeked', flushLive, true);

  window.ViuSubRecorder = {
    version: '5.1',
    payload: payload,
    reset: function () {
      cues.length = 0;
      live.clear();
      statCache.at = 0;
      statCache.gaps = [];
      statCache.covered = 0;
      return true;
    }
  };

  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(loop);
  } else {
    setInterval(tick, POLL_MS);
  }
})();
"""

    /**
     * Membaca state perekam untuk ditampilkan di panel app.
     *
     * Cue dipangkas ke PREVIEW_MAX terakhir di sini, di sisi JS, SEBELUM
     * di-stringify. Kalau tidak, tiap polling ikut membawa seluruh rekaman:
     * pada menit ke-90 itu sekitar 1500 cue / 200 KB, di-stringify beberapa
     * kali per detik, sia-sia karena panel hanya menampilkan 30 baris.
     *
     * Mengembalikan null kalau skrip belum terpasang di halaman ini.
     */
    const val READ_PAYLOAD = """
(function () {
  if (!window.ViuSubRecorder) return JSON.stringify(null);
  var p = window.ViuSubRecorder.payload();
  if (!p) return JSON.stringify(null);
  if (Array.isArray(p.cues) && p.cues.length > $PREVIEW_MAX) {
    p.truncatedFrom = p.cueCount - $PREVIEW_MAX;
    p.cues = p.cues.slice(-$PREVIEW_MAX);
  }
  return JSON.stringify(p);
})()
"""

    /**
     * Membaca seluruh rekaman tanpa pemangkasan.
     *
     * Dipakai hanya saat tombol ekspor ditekan, bukan saat polling. Polling
     * memakai READ_PAYLOAD yang dipangkas ke PREVIEW_MAX, karena panel hanya
     * menampilkan segelintir baris terakhir. Kalau ekspor ikut memakai yang
     * dipangkas, file SRT untuk episode panjang akan berisi 30 cue terakhir
     * saja, tanpa peringatan sama sekali.
     */
    const val READ_FULL = """
(function () {
  if (!window.ViuSubRecorder) return JSON.stringify(null);
  return JSON.stringify(window.ViuSubRecorder.payload());
})()
"""

    /** Menghapus rekaman di halaman yang sedang terbuka. */
    const val RESET = "window.ViuSubRecorder && window.ViuSubRecorder.reset()"
}