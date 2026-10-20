package dev.ifan.viusub

import java.util.Locale

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

  // Teks subtitle yang tampil tepat sebelum seek. Overlay pemutar sering
  // masih memperlihatkan teks itu beberapa saat setelah lompatan; teks
  // yang sama tidak boleh dicatat sebagai cue baru di posisi tujuan.
  // Penahan dilepas begitu overlay benar-benar berganti atau menghilang.
  var staleTexts = null;

  // Bitmovin kadang berhenti di frame pertama setelah seek/rewind walau
  // statusnya masih "playing". Mendorong posisi 0,5 detik biasanya memancing
  // pemutar mengambil segmen lagi tanpa perlu memuat ulang halaman.
  var seekRecovery = {
    at: 0,
    tries: 0,
    base: -1,
    nudgeTarget: -1,
    nudgePending: false,
    seekingSince: 0,
    uiPendingUntil: 0,
    lastTime: -1,
    lastProgress: 0,
    lastNudge: 0
  };

  // Hasil terakhir findReplay per teks. Overlay subtitle Viu bisa membuat
  // ulang elemen yang sama berkali-kali; tanpa cache, setiap elemen baru
  // memicu pemindaian seluruh cue. Cache dibuang setiap kali cue berubah.
  var replayCache = {};

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

  // Semua <video> yang bisa dijangkau, termasuk yang berada di shadow root
  // terbuka dan iframe se-origin. getVideo() hanya memindai dokumen utama,
  // jadi elemen pemutar yang dibungkus komponen web bisa lolos.
  function allVideos() {
    var found = [];
    var roots = [document];
    while (roots.length) {
      var root = roots.pop();
      var vids = [];
      try { vids = root.querySelectorAll('video'); } catch (e) { vids = []; }
      for (var i = 0; i < vids.length; i++) {
        if (found.indexOf(vids[i]) === -1) found.push(vids[i]);
      }
      var nodes = [];
      try { nodes = root.querySelectorAll('*'); } catch (e) { nodes = []; }
      for (var j = 0; j < nodes.length; j++) {
        var node = nodes[j];
        if (node.shadowRoot && roots.indexOf(node.shadowRoot) === -1) {
          roots.push(node.shadowRoot);
        }
        if (node.tagName === 'IFRAME') {
          var doc = null;
          try { doc = node.contentDocument; } catch (e) { doc = null; }
          if (doc && roots.indexOf(doc) === -1) roots.push(doc);
        }
      }
    }
    return found;
  }

  // Memilih video yang paling masuk akal untuk lompatan. Selain luas dan
  // status putar, durasi dipakai sebagai penyaring: cue pada menit ke-60
  // tidak mungkin berada di video iklan/pratinjau yang hanya 30 detik.
  function seekVideo(target) {
    var hint = getVideo();
    var list = allVideos();
    var best = null, bestScore = -1;
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      var area = 0;
      try {
        var r = v.getBoundingClientRect();
        area = Math.max(0, r.width) * Math.max(0, r.height);
      } catch (e) { area = 0; }
      var playing = !v.paused && !v.ended;
      var ready = v.readyState > 0 || v.videoWidth > 0;
      var longEnough = !(isFinite(v.duration) && v.duration > 0) ||
        v.duration + 1 >= target;
      var score = (area + 1) *
        (playing ? 2 : 1) *
        (ready ? 2 : 1) *
        (longEnough ? 4 : 1);
      if (v === hint) score *= 1.5;
      if (score > bestScore) { bestScore = score; best = v; }
    }
    if (best) return best;
    return hint || (list.length ? list[0] : null);
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

  // Membandingkan teks tanpa beda spasi atau kapital.
  function normText(text) {
    return String(text || '').replace(/\s+/g, ' ').trim().toLowerCase();
  }

  // Cue lama bisa berupa gabungan beberapa baris. Saat diputar ulang,
  // Viu kadang menampilkan baris-baris itu terpisah lagi.
  function sameSubtitle(a, b) {
    if (!a || !b) return false;
    if (a === b) return true;
    if (a.length < 6 || b.length < 6) return false;
    return a.indexOf(b) !== -1 || b.indexOf(a) !== -1;
  }

  // Mencari cue selesai yang sekarang tampil lagi. Ini yang membedakan
  // "memutar ulang bagian lama" dari "merekam cue baru": kalau teks dan
  // waktunya cocok dengan cue lama, jangan beri nomor baru dan jangan
  // biarkan end-nya kosong.
  function findReplay(text, now) {
    var norm = normText(text);
    if (!norm) return null;

    var cached = replayCache[norm];
    if (cached && now >= cached.start - 1.0 && now <= cached.end + 1.0) {
      return cached;
    }

    var best = null;
    var bestDist = Infinity;
    for (var i = cues.length - 1; i >= 0; i--) {
      var c = cues[i];
      // Toleransi 1 detik: overlay saat diputar ulang bisa muncul sedikit
      // lebih awal atau lebih lambat dari rekaman pertama.
      if (now < c.start - 1.0 || now > c.end + 1.0) continue;
      if (!sameSubtitle(c._norm || normText(c.text), norm)) continue;
      var dist = Math.abs(now - c.start);
      if (dist < bestDist) {
        best = c;
        bestDist = dist;
      }
    }

    if (best) replayCache[norm] = best;
    else if (cached) delete replayCache[norm];
    return best;
  }

  function scan() {
    var v = getVideo();
    if (!v) return;
    var now = v.currentTime;
    if (!now) return;
    lastTime = now;

    var cands = collect();

    // Overlay subtitle di Viu diperbarui beberapa saat setelah currentTime
    // melompat. Selama teks yang sama dengan sebelum seek masih terlihat,
    // itu sisa posisi lama, bukan cue baru di posisi tujuan.
    if (staleTexts) {
      var staleStillShown = false;
      for (var s = 0; s < cands.length; s++) {
        if (staleTexts.has(cands[s].text)) { staleStillShown = true; break; }
      }
      // Teks lama sudah tergantikan atau hilang; cue berikutnya diterima.
      if (!staleStillShown) staleTexts = null;
    }

    var present = new Set();

    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      if (staleTexts && staleTexts.has(c.text)) continue;
      present.add(c.el);

      var rec = live.get(c.el);
      var oldCue = null;
      if (!rec) {
        oldCue = findReplay(c.text, now);
        live.set(c.el, {
          start: oldCue ? oldCue.start : now,
          text: c.text,
          top: c.top,
          replayCue: oldCue
        });
      } else if (rec.text !== c.text) {
        // Cue ulang tidak pernah masuk daftar rekaman.
        if (!rec.replayCue) push(rec.start, now, rec.text, rec.top);
        oldCue = findReplay(c.text, now);
        live.set(c.el, {
          start: oldCue ? oldCue.start : now,
          text: c.text,
          top: c.top,
          replayCue: oldCue
        });
      } else {
        rec.top = c.top;
      }
    }

    live.forEach(function (rec, el) {
      if (!present.has(el)) {
        if (!rec.replayCue) push(rec.start, now, rec.text, rec.top);
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
    var stale = null;
    live.forEach(function (rec) {
      if (!rec.replayCue && end > rec.start) {
        push(rec.start, end, rec.text, rec.top);
      }
      if (!stale) stale = new Set();
      stale.add(rec.text);
    });
    live.clear();
    // Simpan teks yang barusan tampil supaya scan sesudah seek tidak
    // menganggap overlay lama sebagai subtitle baru di posisi tujuan.
    if (stale) staleTexts = stale;
  }

  /**
   * Bitmovin kadang berhenti di frame pertama sesudah seek/rewind walau
   * statusnya masih "playing": currentTime tidak maju, gambar diam. Seek
   * kecil ke posisi lain adalah cara yang dilaporkan berhasil di forum
   * Bitmovin — decoder dipancing mengambil segmen lagi.
   *
   * Jendela pemulihan dibuka oleh event seeking/seeked. Kalau tidak ada
   * kemajuan selama 4 detik di dalam jendela itu, posisi didorong 0,5 detik
   * lalu 1,0 detik dari posisi macet, maksimal dua kali. Setiap seek baru
   * dari pengguna membuka jendela baru. Saat video di-pause, pemulihan tidak
   * berjalan, jadi berhenti untuk membaca subtitle tidak terganggu.
   */
  function armSeekRecovery(ev) {
    var now = Date.now();
    var v = getVideo();
    var t = v ? v.currentTime : -1;

    if (ev && ev.type === 'seeking') {
      seekRecovery.seekingSince = now;
    } else if (ev && ev.type === 'seeked') {
      seekRecovery.seekingSince = 0;
    }

    // Event seeking/seeked milik dorongan kita sendiri tidak membuka
    // jendela baru; kalau dibuka, percobaan kedua tidak akan pernah habis.
    // Nudge bisa dilaporkan pemutar pada posisi lama atau posisi target,
    // jadi keduanya dianggap milik watchdog. Seek pengguna ke posisi lain
    // tetap dianggap baru.
    var mine = false;
    if (seekRecovery.nudgePending) {
      var recent = seekRecovery.lastNudge &&
        now - seekRecovery.lastNudge < 3000;
      var nearTarget = seekRecovery.nudgeTarget >= 0 && t >= 0 &&
        Math.abs(t - seekRecovery.nudgeTarget) < 0.6;
      var nearBase = seekRecovery.base >= 0 && t >= 0 &&
        Math.abs(t - seekRecovery.base) < 0.6;
      mine = recent && (t < 0 || nearTarget || nearBase);
      if (mine) {
        seekRecovery.nudgePending = false;
        seekRecovery.nudgeTarget = -1;
      }
    }

    // Setiap event seeking yang bukan berasal dari nudge adalah seek baru
    // dari pengguna: buka jendela pemulihan baru dan reset jatah percobaan.
    if (ev && ev.type === 'seeking' && !mine) {
      seekRecovery.at = now;
      seekRecovery.tries = 0;
      seekRecovery.base = -1;
      seekRecovery.nudgeTarget = -1;
      seekRecovery.nudgePending = false;
      // Hitung mundur 4 detik dari seek ini, bukan dari kemajuan terakhir
      // sebelum pengguna mengetuk baris.
      if (t >= 0) {
        seekRecovery.lastTime = t;
        seekRecovery.lastProgress = now;
      }
    }

    // Jangan menyegarkan timer hanya karena event seeked berulang: kalau
    // posisi tidak berubah, itu tetap macet dan harus terdeteksi. Timer
    // hanya disegarkan saat currentTime benar-benar bergerak.
    if (t >= 0 && (seekRecovery.lastTime < 0 ||
        Math.abs(t - seekRecovery.lastTime) > 0.05)) {
      seekRecovery.lastTime = t;
      seekRecovery.lastProgress = now;
    }
  }

  function watchSeekRecovery() {
    var v = getVideo();
    if (!v) return;
    var now = Date.now();
    if (!isFinite(v.currentTime)) return;

    if (v.paused || v.ended || !seekRecovery.at) {
      // Saat di-pause, jendela pemulihan tetap dijaga: bisa saja pengguna
      // menghentikan video dulu untuk membaca subtitle, lalu menekan play
      // dan pemutar tetap macet di posisi hasil rewind.
      if (seekRecovery.at && now - seekRecovery.at > 15000) {
        seekRecovery.at = now;
        seekRecovery.tries = 0;
        seekRecovery.base = -1;
        seekRecovery.nudgeTarget = -1;
        seekRecovery.nudgePending = false;
      }
      seekRecovery.lastTime = v.currentTime;
      seekRecovery.lastProgress = now;
      return;
    }

    // Posisi maju: pemutar hidup, bukan macet.
    if (Math.abs(v.currentTime - seekRecovery.lastTime) > 0.05) {
      seekRecovery.lastTime = v.currentTime;
      seekRecovery.lastProgress = now;
      seekRecovery.tries = 0;
      seekRecovery.base = -1;
      seekRecovery.nudgeTarget = -1;
      seekRecovery.nudgePending = false;
      return;
    }

    if (now - seekRecovery.at > 15000) return;
    if (now - seekRecovery.lastProgress < 4000) return;
    if (seekRecovery.tries >= 2) return;
    if (now - seekRecovery.lastNudge < 4000) return;

    // Basis tetap dari posisi macet pertama. Percobaan kedua harus memakai
    // target yang berbeda; kalau nudge pertama diabaikan pemutar, memakai
    // currentTime + 0.5 lagi akan mengirim posisi yang sama persis.
    if (seekRecovery.base < 0) seekRecovery.base = v.currentTime;

    seekRecovery.tries++;
    var offset = 0.5 * seekRecovery.tries;
    var step = seekRecovery.base + offset;
    if (isFinite(v.duration) && v.duration > 0 && step > v.duration - 0.2) {
      step = Math.max(0.1, seekRecovery.base - offset);
    }

    seekRecovery.nudgeTarget = step;
    seekRecovery.nudgePending = true;
    seekRecovery.lastNudge = now;
    seekRecovery.lastProgress = now;
    try { v.currentTime = step; } catch (e) {}
  }

  function push(start, end, text, top) {
    if (end - start < 0.15) return;
    var last = cues[cues.length - 1];

    if (isDuplicate(text, start, end)) return;

    if (last) {
      var gap = start - last.end;
      var sameTime = Math.abs(start - last.start) < 0.4;

      if (last.text === text && gap < 0.15 && gap > -0.2) {
        last.end = Math.max(last.end, end);
        statCache.at = 0;
        replayCache = {};
        return;
      }

      if (sameTime && gap < 0.35) {
        if (!last._parts) {
          last._parts = [{ t: last.text, y: (last._top || 0) }];
        }
        last._parts.push({ t: text, y: (top || 0) });
        last._parts.sort(function (a, b) { return a.y - b.y; });
        last.text = last._parts.map(function (p) { return p.t; }).join(' ');
        last._norm = normText(last.text);
        last.end = Math.max(last.end, end);
        statCache.at = 0;
        replayCache = {};
        return;
      }
    }

    cues.push({
      start: +start.toFixed(3),
      end: +end.toFixed(3),
      text: text,
      _top: top || 0,
      _norm: normText(text)
    });
    statCache.at = 0;
    replayCache = {};
  }

  function isDuplicate(text, start, end) {
    var norm = normText(text);
    // Periksa seluruh rekaman, bukan hanya 40 cue terakhir: saat pengguna
    // memundurkan video jauh ke belakang, cue pembanding bisa jauh di atas.
    for (var i = cues.length - 1; i >= 0; i--) {
      var c = cues[i];
      if (!sameSubtitle(c._norm || normText(c.text), norm)) continue;
      // Rentang waktu beririsan berarti ini cue yang sama yang diputar
      // ulang, bukan dialog baru di waktu lain.
      if (start < c.end + 0.35 && end > c.start - 0.35) return true;
      // Toleransi lama untuk cue pendek yang start-nya hampir sama.
      if (Math.abs(c.start - start) < 0.8) return true;
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

  function pendingPayload(list) {
    var items = [];
    live.forEach(function (r) { items.push(r); });
    items.sort(function (a, b) { return a.start - b.start; });

    var lastCue = list[list.length - 1];
    var out = [];
    var seenReplay = {};
    var nextIndex = list.length + 1;

    for (var i = 0; i < items.length; i++) {
      var p = items[i];
      if (p.replayCue) {
        // Satu cue lama bisa terdiri dari beberapa elemen subtitle.
        // Tampilkan sekali saja, memakai teks lengkap dan end cue lama.
        var idx = list.indexOf(p.replayCue) + 1;
        if (idx < 1 || seenReplay[idx]) continue;
        seenReplay[idx] = true;
        out.push({
          i: idx,
          start: ts(p.replayCue.start, '.'),
          end: ts(p.replayCue.end, '.'),
          duration: (Math.round((p.replayCue.end - p.replayCue.start) * 100) / 100).toFixed(2),
          replay: true,
          text: p.replayCue.text
        });
      } else {
        // Buang live yang sebenarnya hanya sisa cue terakhir yang baru
        // selesai (overlay masih tampil sesaat).
        if (lastCue && p.text === lastCue.text &&
            Math.abs(p.start - lastCue.start) < 0.4) continue;
        out.push({ i: nextIndex++, start: ts(p.start, '.'), text: p.text });
      }
    }
    return out;
  }

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
    var pending = pendingPayload(list);
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
      pending: pending.length,
      // Selalu nol: perekam tidak lagi memulihkan simpanan lama, jadi
      // tidak ada sesi sebelumnya yang bisa dilanjutkan.
      resumedFrom: 0,
      restoredCount: 0,
      filters: {
        bmpui: rejects.bmpui, leaf: rejects.leaf, empty: rejects.empty,
        long: rejects.long, symbol: rejects.symbol, timecode: rejects.timecode,
        closest: rejects.closest, geometry: rejects.geometry
      },
      pendingCues: pending,
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
    seekRecovery.at = 0;
    seekRecovery.tries = 0;
    seekRecovery.base = -1;
    seekRecovery.nudgeTarget = -1;
    seekRecovery.nudgePending = false;
    seekRecovery.seekingSince = 0;
    seekRecovery.uiPendingUntil = 0;
    seekRecovery.lastTime = -1;
    seekRecovery.lastProgress = 0;
    seekRecovery.lastNudge = 0;
    replayCache = {};
    return true;
  }

  function tick() {
    checkRoute();
    scan();
    watchSeekRecovery();
  }

  function loop(now) {
    if (now - lastTick >= POLL_MS) { lastTick = now; tick(); }
    requestAnimationFrame(loop);
  }

  document.addEventListener('seeking', flushLive, true);
  document.addEventListener('seeked', flushLive, true);
  document.addEventListener('seeking', armSeekRecovery, true);
  document.addEventListener('seeked', armSeekRecovery, true);

  // Kontrol Home pada seek bar Bitmovin memanggil player.seek(0), jalur yang
  // sama dengan tombol replay. Ini jauh lebih aman daripada menulis
  // currentTime langsung di detik-detik awal, yang membuat Bitmovin memuat
  // ulang stream lalu berhenti di frame pertama.
  function seekToStartViaUi(v) {
    var roots = [];
    try {
      var box = v && v.closest ? v.closest('.bmpui-ui-player') : null;
      if (box) roots.push(box);
    } catch (e) {}
    try {
      var r = v && v.getRootNode ? v.getRootNode() : null;
      if (r && roots.indexOf(r) === -1) roots.push(r);
    } catch (e) {}
    if (roots.indexOf(document) === -1) roots.push(document);

    var selectors = [
      '.bmpui-ui-seekbar',
      '[class*="bmpui"][class*="seekbar"]',
      '[class*="seek"][role="slider"]',
      '[aria-label*="seek" i][role="slider"]',
      '[aria-label*="posisi" i][role="slider"]'
    ];
    for (var s = 0; s < selectors.length; s++) {
      for (var q = 0; q < roots.length; q++) {
        var list = [];
        try { list = roots[q].querySelectorAll(selectors[s]); } catch (e) { list = []; }
        for (var i = 0; i < list.length; i++) {
          var el = list[i];
          try { el.focus(); } catch (e) {}
          try {
            var ev = new KeyboardEvent('keydown', {
              key: 'Home',
              code: 'Home',
              keyCode: 36,
              which: 36,
              bubbles: true,
              cancelable: true
            });
            try {
              if (ev.keyCode !== 36) {
                Object.defineProperty(ev, 'keyCode', { get: function () { return 36; } });
                Object.defineProperty(ev, 'which', { get: function () { return 36; } });
              }
            } catch (e) {}
            el.dispatchEvent(ev);
            return true;
          } catch (e) {}
        }
      }
    }
    return false;
  }

  // Jalan darurat kalau kontrol Home tidak tersedia atau tidak bereaksi.
  // Video dijeda dulu supaya Bitmovin tidak menahan frame sambil menunggu
  // buffer, lalu play lagi setelah seeked (dengan batas waktu).
  function directSeek(v, target, guard) {
    if (!guard) {
      v.currentTime = target;
      return;
    }
    var wasPlaying = !v.paused && !v.ended;
    if (wasPlaying) {
      try { v.pause(); } catch (e) {}
      var resumed = false;
      var resume = function () {
        if (resumed) return;
        resumed = true;
        try { v.removeEventListener('seeked', resume); } catch (e) {}
        if (!v.paused) return;
        try {
          var p = v.play();
          if (p && p.catch) p.catch(function () {});
        } catch (e) {}
      };
      try { v.addEventListener('seeked', resume, { once: true }); } catch (e) {}
      setTimeout(resume, 1500);
    }
    v.currentTime = target;
  }

  function armUserSeek(at) {
    seekRecovery.seekingSince = at;
    seekRecovery.at = at;
    seekRecovery.tries = 0;
    seekRecovery.base = -1;
    seekRecovery.nudgeTarget = -1;
    seekRecovery.nudgePending = false;
    seekRecovery.uiPendingUntil = 0;
    seekRecovery.lastProgress = at;
  }

  window.ViuSubRecorder = {
    version: '5.6',
    payload: payload,
    // Memakai getVideo() yang sama dengan perekam, bukan video pertama di
    // DOM: halaman Viu bisa punya lebih dari satu <video> (iklan/pratinjau),
    // dan yang pertama belum tentu yang sedang tampil.
    seek: function (seconds) {
      var v = seekVideo(seconds);
      if (!v) return JSON.stringify({ ok: false, reason: 'no-video' });
      var d = (isFinite(v.duration) && v.duration > 0) ? v.duration : seconds;
      var target = Math.max(0, Math.min(seconds, d));
      // Cue pertama biasanya ada di detik 0,1–0,5. Menulis currentTime di
      // rentang itu membuat Bitmovin memuat ulang awal stream dan berhenti.
      var early = target < 1.0;
      // Cue yang masih live akan ditutup oleh event seeking. Mereka sudah
      // ada sebelum lompatan, jadi ikut dihitung sebagai cue lama.
      var boundary = cues.length + live.size;
      // Klik berulang pada baris yang sama tidak perlu menyentuh pemutar
      // lagi. Toleransi cue awal lebih lebar karena jalur Home/fallback
      // bisa mendarat di 0 atau 0,5 detik.
      var near = early ? 0.5 : 0.3;
      if (Math.abs(v.currentTime - target) < near) {
        return JSON.stringify({
          ok: true,
          target: target,
          cueCount: boundary
        });
      }
      var now = Date.now();
      // Dua seek yang dikirim beruntun saat Bitmovin masih memproses seek
      // pertama bisa membuat pemutar diam di frame pertama. Tolak seek baru
      // selama seek masih segar; kalau seek sudah macet lebih dari 2,5 detik,
      // izinkan tap baru sebagai upaya pemulihan. uiPendingUntil menahan tap
      // kedua saat seek awal dikirim lewat kontrol Home (v.seeking belum
      // tentu langsung true).
      if (v.seeking || now < seekRecovery.uiPendingUntil) {
        var since = seekRecovery.seekingSince || (now - 2500);
        if (now - since < 2500) {
          return JSON.stringify({
            ok: false,
            reason: 'busy',
            target: target
          });
        }
      }
      if (early) {
        var before = v.currentTime;
        if (seekToStartViaUi(v)) {
          var homeAt = Date.now();
          armUserSeek(homeAt);
          seekRecovery.uiPendingUntil = homeAt + 1500;
          // Kalau kontrol Home tidak tersedia/bereaksi, pakai currentTime
          // dengan jeda aman setelah 0,9 detik. Posisi yang sudah bergerak
          // mendekati awal tidak boleh ditimpa.
          setTimeout(function () {
            try {
              if (!v.seeking && v.currentTime > 3.0 &&
                  v.currentTime >= before - 0.5) {
                var safe = Math.max(target, Math.min(0.5, d));
                directSeek(v, safe, true);
                armUserSeek(Date.now());
                seekRecovery.uiPendingUntil = Date.now() + 1200;
              }
            } catch (e) {}
          }, 900);
          return JSON.stringify({
            ok: true,
            target: target,
            cueCount: boundary
          });
        }
        var safeTarget = Math.max(target, Math.min(0.5, d));
        try {
          directSeek(v, safeTarget, true);
          armUserSeek(Date.now());
        } catch (e) {
          return JSON.stringify({ ok: false, reason: 'seek-error' });
        }
        return JSON.stringify({
          ok: true,
          target: safeTarget,
          cueCount: boundary
        });
      }
      try {
        directSeek(v, target, false);
        armUserSeek(Date.now());
      } catch (e) {
        return JSON.stringify({ ok: false, reason: 'seek-error' });
      }
      return JSON.stringify({
        ok: true,
        target: target,
        cueCount: boundary
      });
    },
    reset: function () {
      cues.length = 0;
      live.clear();
      statCache.at = 0;
      statCache.gaps = [];
      statCache.covered = 0;
      seekRecovery.at = 0;
      seekRecovery.tries = 0;
      seekRecovery.base = -1;
      seekRecovery.nudgeTarget = -1;
      seekRecovery.nudgePending = false;
      seekRecovery.seekingSince = 0;
      seekRecovery.uiPendingUntil = 0;
      seekRecovery.lastTime = -1;
      seekRecovery.lastProgress = 0;
      seekRecovery.lastNudge = 0;
      replayCache = {};
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
     * kali per detik. Panel tetap menampilkan seluruh rekaman; snapshot
     * penuh diambil terpisah lewat READ_FULL saat pertama kali atau saat
     * terdeteksi ada celah.
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
     * Dipakai untuk ekspor dan untuk melengkapi daftar transkrip secara
     * otomatis, bukan saat polling. Polling memakai READ_PAYLOAD yang
     * dipangkas ke PREVIEW_MAX supaya tetap ringan. Kalau ekspor ikut
     * memakai yang dipangkas, file SRT untuk episode panjang akan berisi
     * 30 cue terakhir saja, tanpa peringatan sama sekali.
     */
    const val READ_FULL = """
(function () {
  if (!window.ViuSubRecorder) return JSON.stringify(null);
  return JSON.stringify(window.ViuSubRecorder.payload());
})()
"""

    /**
     * Melompat ke detik tertentu di video yang sedang diputar.
     *
     * Memakai window.ViuSubRecorder.seek() supaya elemen <video> yang
     * dipilih memakai heuristik yang sama dengan perekam — bukan video
     * pertama di DOM yang bisa saja iklan atau pratinjau. Hasilnya JSON:
     * {"ok":true,...} kalau posisi diterapkan, atau
     * {"ok":false,"reason":"no-script"|"no-video"|"seek-error"|"busy"}
     * kalau tidak. Pemanggil memakai reason untuk memutuskan perlu mencoba
     * lagi; "busy" berarti seek pertama belum selesai dan tidak boleh
     * ditumpuk.
     *
     * Cue pertama (target di bawah 1 detik) memakai kontrol Home pada seek
     * bar Bitmovin supaya pemutar sendiri yang melakukan seek ke awal;
     * menulis currentTime langsung di rentang itu membuat Bitmovin memuat
     * ulang stream lalu berhenti di frame pertama.
     */
    fun seek(seconds: Double): String {
        val t = String.format(Locale.US, "%.3f", seconds)
        return "(function(){" +
            "var r=window.ViuSubRecorder;" +
            "if(r&&typeof r.seek==='function')return r.seek($t);" +
            "return JSON.stringify({ok:false,reason:'no-script'})})()"
    }

    /** Menghapus rekaman di halaman yang sedang terbuka. */
    const val RESET = "window.ViuSubRecorder && window.ViuSubRecorder.reset()"
}