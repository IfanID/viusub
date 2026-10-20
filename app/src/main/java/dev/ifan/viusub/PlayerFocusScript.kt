package dev.ifan.viusub

/**
 * Skrip "hanya pemain": menyembunyikan seluruh halaman di luar pemain —
 * header, info episode, rekomendasi, footer, overlay portal — lalu
 * mengangkat zona pemain menjadi tetap memenuhi layar WebView.
 *
 * Sembunyikannya bukan dengan menulis ulang situs, tapi dengan menandai
 * elemen lewat data-attribute plus satu <style> berisi !important. React
 * tidak menghapus attribute yang tidak ia kelola, dan loop 250 ms
 * memasang ulang tanda pada elemen yang dirender ulang, jadi pola ini
 * tahan terhadap hidrasi dan re-render.
 *
 * Dua fakta yang menentukan desainnya:
 *
 * 1. Kelas CSS halaman Viu semuanya dinamis (css-xxy1q dan sejenisnya),
 *    jadi zona pemain dicari lewat struktur, bukan nama kelas: dari SSR
 *    halaman episode, zona pemain adalah salah satu anak langsung
 *    <main> (setelah <header>), dan <video>-nya dirender klien. Ancestor
 *    <video> pertama yang pembungkusnya <main> itulah zonanya.
 *
 * 2. Penyembunyian berhenti di level zona — bagian dalam zona tetap
 *    utuh. Overlay subtitle dan tombol pemain ada di dalamnya; kalau
 *    saudara <video> ikut disembunyikan, getBoundingClientRect()
 *    mengembalikan 0x0, filter geometri perekam menolak semua kandidat,
 *    dan badge angka membeku.
 *
 * 3. Kotak <video> dibikin 16:9 menempel ke atas, bukan memenuhi layar.
 *    Bila kotaknya dipaksa 100vh, isi stream ber-mengandung (contain)
 *    dan melayang di tengah — persis keluhan uji perangkat: gambar
 *    nyangkut di tengah layar dikelilingi hitam. Kotak 16:9 mempertahankan
 *    lebar penuh, tinggi mengikuti rasio, dan subtitle overlay Viu ikut
 *    naik karena ber-anker ke kotak video yang sama.
 *
 * Tidak memakai iframe: situs ini memasang x-frame-options SAMEORIGIN,
 * dan perekam juga bekerja di frame yang sama.
 */
object PlayerFocusScript {

    /**
     * Dipasang lewat evaluateJavascript bersama perekam: sejak
     * onPageStarted, onPageFinished, dan dipasang ulang tiap ~2 detik
     * dari aplikasi. Guardnya hanya menolak bila install sebelumnya
     * masih sehat (jantung masih berdenyut); install mayat diganti total.
     *
     * Tanda berbentuk:
     *   data-vsu-hide   -> tampil: none
     *   data-vsu-player -> zona pemain, fixed memenuhi layar
     *   data-vsu-fill   -> rantai lebar dari zona turun ke pemain
     *   data-vsu-lock   -> html/body, matikan scroll
     *
     * Semua tanda dicopot bila URL berpaling dari halaman episode, jadi
     * navigasi SPA di dalam WebView tidak meninggalkan sisa.
     */
    const val SOURCE = """
(function () {
  'use strict';
  if (window.top !== window.self) return;
  // Hanya jantung yang masih berdenyut (tik <5 detik lalu) yang boleh
  // menolak pemasangan ulang. Install mayat — tanda tertulis tapi jantung
  // beku/tak ada — diganti total, sebab seluruh state-nya di luar window.
  var heart = window.__ViuSubHeart | 0;
  if (window.__ViuSubFocus && heart && (new Date()).getTime() - heart < 5000) return;
  window.__ViuSubFocus = true;
  window.__ViuSubHeart = 0;

  var PAGE_RE = /\/ott\/[^\/]+\/[^\/]+\/vod\//;
  var CSS_ID = 'viu-sub-focus-css';
  var ATTR_PLAYER = 'data-vsu-player';
  var ATTR_HIDE = 'data-vsu-hide';
  var ATTR_FILL = 'data-vsu-fill';
  var ATTR_LOCK = 'data-vsu-lock';
  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, LINK: 1, META: 1 };

  var chromeDone = false;
  var zoneDone = false;

  function injectCss() {
    if (document.getElementById(CSS_ID)) return;
    // `head` bisa belum ada saat pemantangan dini (onPageStarted) —
    // melempar di sini berarti tik pertama mati dan interval tak lahir.
    var root = document.head || document.documentElement;
    if (!root) return;
    var st = document.createElement('style');
    st.id = CSS_ID;
    st.textContent =
      '[data-vsu-hide]{display:none!important}' +
      '[data-vsu-player]{position:fixed!important;top:0!important;left:0!important;' +
      'right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;' +
      'max-width:none!important;max-height:none!important;aspect-ratio:auto!important;' +
      'z-index:2147483000!important;background:#000!important;overflow:hidden!important;' +
      'margin:0!important;padding:0!important}' +
      '[data-vsu-fill]{width:100%!important;max-width:none!important;' +
      'max-height:100vh!important;align-self:flex-start!important;margin:0!important}' +
      '[data-vsu-player] video{width:100%!important;height:auto!important;' +
      'aspect-ratio:16/9!important;max-height:100vh!important;display:block!important;' +
      'object-fit:contain!important;background:#000!important}' +
      '[data-vsu-lock]{overflow:hidden!important;height:100%!important}';
    (document.head || document.documentElement).appendChild(st);
  }

  /** Wrapper tahan lempar: kesalahan tak boleh membunuh interval. */
  function safeTick() {
    try {
      tick();
      // Jantung: catat kapan tik terakhir benar-benar hidup; guard di atas
      // memakainya untuk menilai install masih hidup atau mayat.
      window.__ViuSubHeart = (new Date()).getTime();
    } catch (err) {}
  }

  /** "1" saat zona pemain sudah dipromosikan; dibaca layar sapaan. */
  window.__ViuSubFocusReady = '0';

  function markHidden(el) {
    if (!el || SKIP[el.tagName]) return;
    // Rambu tetap: kontrol dan overlay subtitle pemain hidup di elemen
    // bmpui. Apa pun yang bmpui — atau wadah yang MEMUAT elemen bmpui —
    // tidak boleh disembunyikan, dari jalur mana pun ia datang. Wadah
    // halaman bersih (header, info, daftar episode, footer) tak memuat
    // bmpui, jadi tetap tersembui.
    try {
      if (IS_BMPUI(el)) return;
      if (el.querySelector &&
          el.querySelector('[class*="bmpui"],[id*="bmpui"]')) return;
    } catch (err) { return; }
    el.setAttribute(ATTR_HIDE, '1');
  }

  /**
   * Tahap pertama: bersihkan yang sudah jelas sebelum pemain terpasang —
   * portal di luar root aplikasi dan header dalam main. Sisa halaman
   * menunggu zona pemain ditemukan, supaya bagian dalam zona tidak ikut
   * tersapu.
   */
  function hideChrome() {
    // Dijalankan SETIAP tik, bukan sekali: simpul baru dari hidrasi dan
    // perpindahan SPA di dalam situs dikejar terus — kejaran inilah
    // penyembuhannya; tanda lama pada simpul yang masih hidup tak berubah.
    //
    // Membukukan SEMUA anak <main>, termasuk pembungkus pemain: tidak ada
    // lagi tebakan "div pertama tersimpan". Heuristik itu gagal bila
    // varian halaman menaruh Drawer-root sebagai anak pertama dan zona
    // pemain tersapu bersama sisanya. Zona sejati dibuka kembali lewat
    // promote() — rangkainya tak ikut tersembui.
    var app = document.getElementById('__next');
    if (!app) return;
    var outer = document.body.children;
    for (var i = 0; i < outer.length; i++) {
      if (outer[i] !== app) markHidden(outer[i]);
    }
    var main = app.querySelector('main');
    if (!main) return;
    var kids = main.children;
    for (var j = 0; j < kids.length; j++) {
      markHidden(kids[j]);
    }
    chromeDone = true;
  }

  /**
   * <video> "pemain" = ber-area terbesar. Kandidat cadangan: video pertama
   * di luar simpul tersembui, lalu video apa pun — pemain bisa sah
   * berukuran nol sesaat (baru terpasang/tersemat terbalik); audio saja
   * pata layar hitam adalah tanda itu. Tanpa cadangan, zona tidak pernah
   * terpromosikan dan halaman tetap hitam dikehndal tombol CTA.
   */
  function findVideo() {
    var all = document.querySelectorAll('video');
    if (!all.length) return null;
    var best = null;
    var bestArea = 0;
    var plain = null;
    for (var i = 0; i < all.length; i++) {
      var r = all[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (area > 0 && area > bestArea) {
        bestArea = area;
        best = all[i];
      }
      if (!plain && !all[i].closest('[' + ATTR_HIDE + ']')) plain = all[i];
    }
    return best || plain || all[0];
  }

  /**
   * Ancestor <video> pertama yang pembungkus langsungnya <main>, root
   * aplikasi, atau <body>. Kalau pemain hidup di portal di luar main,
   * anchor yang sama tetap bekerja.
   */
  function detectZone(video) {
    var cur = video;
    while (cur) {
      var p = cur.parentElement;
      if (!p) return null;
      if (p.tagName === 'MAIN' || p.id === '__next' || p === document.body) {
        return cur;
      }
      cur = p;
    }
    return null;
  }

  /**
   * Hanya saudara dari tetua zona yang disembunyikan, mulai level zona ke
   * atas: kotak-kotak lain dalam main, isi root di luar main, portal di
   * luar root. Bagian dalam zona tidak disentuh — di sana ada overlay
   * subtitle dan tombol pemain yang harus tetap terekam dan terlihat.
   */
  function hideOffBranch(zone) {
    var cur = zone;
    while (cur && cur.parentElement) {
      var p = cur.parentElement;
      if (p === document.documentElement) break;
      var kids = p.children;
      for (var i = 0; i < kids.length; i++) {
        if (kids[i] !== cur) markHidden(kids[i]);
      }
      cur = p;
    }
  }

  /**
   * Apakah simpul ini bagian kulit pemain (bibson/bmpui). Dari batas ini
   * ke bawah adalah overlay subtitle, kontrol, dan menu pemain — wajib
   * tetap hidup dan tampak, karena perekam membaca overlay subtitle di
   * sananya.
   */
  function IS_BMPUI(el) {
    var cl = typeof el.className === 'string' ? el.className : '';
    return cl.indexOf('bmpui') !== -1 || String(el.id || '').indexOf('bmpui') !== -1;
  }

  function promote(zone, video) {
    // Fase bersih membungkus SEMUA anak <main> — pembungkus zona ikut
    // tersembui. Buka rantai zona dulu (dari <video> naik sampai zona),
    // lalu saudaranya disembunyikan lagi oleh hideOffBranch pada tik ini.
    zone.removeAttribute(ATTR_HIDE);
    var u = video;
    while (u && u !== zone) {
      u.removeAttribute(ATTR_HIDE);
      u = u.parentElement;
    }
    hideOffBranch(zone);
    // Di dalam zona pun ada kotak yang bukan pemain — sisi kanan grid
    // berisi info dan daftar episode yang pada layar sempit tumpuk di
    // bawah pemain. Disembunyikan juga, TAPI setiap calon korban
    // diperiksa lebih dulu: kalau ia sendiri elemen bmpui atau MEMUAT
    // elemen bmpui (pembungkus kontrol, overlay subtitle, menu), ia
    // dilewati. Tidak menebak "kulit" dari rantai video — di sebagian
    // varian <video> justru saudara pembungkus overlay, bukan
    // keturunannya. Yang memuat kontrol pemain pasti selamat.
    // Semua hanya ditandai display:none, tidak ada yang dihapus dari DOM.
    var s = video;
    while (s && s !== zone) {
      var p = s.parentElement;
      if (!p) break;
      var sy = p.children;
      for (var i = 0; i < sy.length; i++) {
        if (sy[i] === s) continue;
        try {
          if (IS_BMPUI(sy[i])) continue;
          if (sy[i].querySelector &&
              sy[i].querySelector('[class*="bmpui"],[id*="bmpui"]')) continue;
        } catch (err) { continue; }
        markHidden(sy[i]);
      }
      s = p;
    }
    zone.setAttribute(ATTR_PLAYER, '1');
    var a = video;
    while (a && a !== zone) {
      a.setAttribute(ATTR_FILL, '1');
      a = a.parentElement;
    }
    document.documentElement.setAttribute(ATTR_LOCK, '1');
    document.body.setAttribute(ATTR_LOCK, '1');
    zoneDone = true;
  }

  function unmarkAll() {
    var sel = '[' + ATTR_HIDE + '],[' + ATTR_PLAYER + '],[' + ATTR_FILL + ']';
    var els = document.querySelectorAll(sel);
    for (var i = 0; i < els.length; i++) {
      els[i].removeAttribute(ATTR_HIDE);
      els[i].removeAttribute(ATTR_PLAYER);
      els[i].removeAttribute(ATTR_FILL);
    }
    document.documentElement.removeAttribute(ATTR_LOCK);
    document.body.removeAttribute(ATTR_LOCK);
    chromeDone = false;
    zoneDone = false;
  }

  function tick() {
    injectCss();
    if (PAGE_RE.test(location.pathname)) {
      hideChrome();
      var video = findVideo();
      if (video) {
        var zone = detectZone(video);
        if (zone) promote(zone, video);
      }
      // Zona yang bertanda bisa ikut hilang bila simpulnya digantikan;
      // turunkan statusnya supaya promote dipasang kembali di tik ini.
      if (zoneDone && !document.querySelector('[' + ATTR_PLAYER + ']')) {
        zoneDone = false;
      }
    } else if (chromeDone || zoneDone) {
      unmarkAll();
    }
    window.__ViuSubFocusReady = zoneDone ? '1' : '0';
  }

  safeTick();
  setInterval(safeTick, 250);
})();
"""

    /**
     * Kode kesiapan untuk layar sapaan:
     *   "2" — zona pemain sudah dipromosikan, tampilan bentuk akhir;
     *   "0" — belum apa-apa.
     *
     * Hanya "2" yang meluruhkan layar sapaan: menunggu di layar sapaan
     * selama pemain belum terpasang lebih baik daripada melepas pengguna
     * ke halaman yang belum bersih. Sengaja tidak menunggu <video>
     * berukuran: di Viu, ukuran video baru terisi setelah mulai diputar.
     */
    const val READ_READY =
        "(function(){return window.__ViuSubFocusReady === '1' ? '2' : '0'})()"
}
