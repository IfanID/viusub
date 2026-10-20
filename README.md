# ViuSub

Perekam subtitle untuk Viu. Menampilkan episode Running Man 2024–2026,
mencatat timing subtitle yang muncul di pemutar, lalu mengekspornya jadi SRT
atau JSON.

Kode perekam tidak pernah tinggal di halaman: ia disuntikkan ke DOM dari app
lewat `evaluateJavascript`, dan hasilnya dibaca kembali oleh app sendiri.

Dibangun dan diuji di Termux (aarch64, PRoot-Distro Arch Linux ARM).

---

## Dua layar

### Home — daftar episode

Launcher aplikasi. Daftar diambil dari API Viu, dikelompokkan per tahun, dan
diurutkan dari episode terbaru ke yang terlama.

```
┌─────────────────────────────────┐
│ Running Man                 ↻   │ ← judul + muat ulang
│ 725 – 823 · 99 episode          │ ← rentang + jumlah
├─────────────────────────────────┤
│ 2026                            │ ← header tahun, menempel saat scroll
│ ╭────╮ Running Man - Ep 823     │
│ │img │ Episode 823 · 01:32:19   │
│ ╰────╯ 2026-10-12               │
│ ─────────────────────────────── │ ← pemisah
│ ╭────╮ Running Man - Ep 822     │
│ …                               │
├─────────────────────────────────┤
│ 99 episode                      │
│ [ 🔍 cari nomor episode…    ✕ ] │ ← bar bawah, ada bayangan
└─────────────────────────────────┘
```

- **Header tahun menempel** di atas saat digulir, dan terdorong ke atas saat
  tahun berikutnya mendekat (`StickyYearDecoration`).
- **Thumbnail** 112×63dp dengan sudut membulat, diambil sendiri oleh
  `ThumbnailLoader` — Coil tidak dipakai karena tidak ada di cache offline.
  Permintaan gambar wajib membawa header `Referer`, tanpa itu Viu menolak.
- **Rentang** dihitung dari daftar yang benar-benar dimuat, bukan angka tetap.
  Begitu Viu menayangkan episode baru, angkanya ikut berubah sendiri.
- **Cache lokal** (`filesDir/episodes.json`) dipakai lebih dulu supaya layar
  tidak kosong saat offline. Muat ulang selalu menulis ulang cache.

### Pemutar — WebView penuh

```
┌─────────────────────────────────┐
│ (12)                            │ ← indikator nomor cue, pojok kiri atas
│                                 │
│            video                │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ ▸ Running Man - Ep 823      │ │ ← bar live, bisa dibuka-tutup
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

- Bar melayang di bawah menampilkan **satu baris subtitle**. Statusnya jujur
  lewat awalan dan warna: `▸ teks` cyan saat cue benar-benar sedang tampil;
  `✓ teks` redup saat cue itu sudah lewat (bar tidak dikosongkan supaya tidak
  berkedip di setiap jeda); dan "Menunggu subtitle…" redup saat belum ada
  cue sama sekali. Diketuk, panel tombol muncul (Pilih Folder, Reset,
  Simpan JSON, Simpan SRT).
- **Hanya pemain** (`PlayerFocusScript`): saat episode dibuka, seluruh chrome
  halaman Viu — header, info episode, rekomendasi, footer — disembunyikan,
  dan zona pemain diangkat memenuhi layar. Kotak video dibikin 16:9 melebar
  penuh dan menempel ke atas, jadi overlay subtitle ikut kotak video (bukan
  jatuh ke dasar layar), sisanya hitam bersama kontrol pemain. Semuanya
  cukup disembunyikan (`display:none`) — tidak ada simpul yang dihapus
  dari DOM, cara sederhana yang teruji. Termasuk di dalam zona: kotak
  sisi grid (info + daftar episode yang pada layar sempit tumpuk di
  bawah pemain) ikut disembunyikan, dan setiap calon korban diperiksa:
  elemen `bmpui` — kontrol, overlay subtitle, menu — atau wadah yang
  memuatnya tidak pernah disembunyikan, dari jalur mana pun. Pembersihannya
  dikejar setiap 250 ms (tahan hidrasi dan perpindahan SPA). Kalau pemain
  tidak muncul, halaman tetap seperti versi bersihnya saja.
- **Layar sapaan**: begitu episode diketuk dari Home, tampil dulu thumbnail,
  judul, dan status "Menyiapkan pemain…" yang titiknya berputar, dengan
  thumbnail berdenyut halus. Layar dipegang sampai zona pemain BETUL-BETUL
  terpasang (`__ViuSubFocusReady = '1'`) — menunggu di layar sapaan lebih
  dipilih daripada melepas pengguna ke gua hitam — lalu meluruh
  memperlihatkan pemain dalam tampilan yang sudah benar, tanpa menunggu
  video mulai diputar. Batas aman 20 detik; layar yang dibuka tanpa
  episode tidak memunculkan layar sapaan.
- **Indikator nomor cue** di pojok kiri atas berisi satu angka saja: jumlah
  cue yang sudah terekam. Hanya tampil saat video **fullscreen**; di tampilan
  biasa disembunyikan supaya pojok layar bersih. Angka diambil dari `cueCount`
  di payload penuh, bukan dari daftar yang dipangkas, jadi tidak pernah macet
  di 30. Sengaja `clickable=false` supaya sentuhan di area itu tetap jatuh ke
  WebView.
- **Fullscreen** didukung lewat `WebChromeClient.onShowCustomView`. Tanpa itu,
  `requestFullscreen()` dari halaman ditolak diam-diam dan tombol fullscreen
  tidak berefek.

---

## Pencarian

Kotak cari menyaring daftar **di memori**, tanpa permintaan jaringan per
ketikan. Daftar hanya ~99 episode, menyaring semuanya lebih cepat daripada
menunggu server.

Aturan teksnya port dari `shared/utils/cari.ts` milik rout-web:

- normalisasi NFC → huruf kecil → NFD → buang diakritik → NFC, lalu tanda baca
  jadi spasi (`Café` = `cafe`, `Ji-hyo` = `ji hyo`)
- **semua** token wajib cocok, bukan salah satu
- skor berjenjang: sama persis 1200, awalan judul 1000−, awalan kata 850,
  potongan 700, token tersebar 620/520
- diurutkan skor menurun, lalu tanggal terbaru, lalu nomor
- satu huruf Latin dianggap terlalu pendek; angka dan Hangul tetap boleh
- hasil yang cocok disorot **cyan tanpa latar belakang**; token yang hanya
  cocok setelah diakritik dibuang tidak dipaksa disorot

**Angka** dicocokkan ke nomor episode dengan `contains`, bukan awalan — rentang
episode 725–823 jadi awalan membuat "4" tidak menemukan apa pun. Tanggal
tidak ikut dicari, supaya "25" tidak memicu semua episode yang tayang tanggal 25.

> **Batasan yang sekarang berlaku:** kolom cari sengaja dibatasi angka saja
> (`inputType="number"`, `digits="0123456789"`), jadi jalur pencarian teks di
> `Cari` belum bisa dipakai dari UI. Kode Jalur teksnya sudah ada dan siap.

---

## Dua kecepatan

Harus dibedakan, karena tidak sama:

| | Kecepatan |
|---|---|
| Rekam subtitle | rAF 40 ms (~25×/detik), sama persis dengan userscript |
| Tampil di panel | polling 100 ms (10×/detik) |

Rekaman jalan di dalam halaman dan tidak pernah lewat polling, jadi kecepatan
deteksinya tidak berubah sejak userscript. Yang lewat polling hanya
perpindahan data ke panel.

Kenapa tidak 40 ms juga: `evaluateJavascript` dipanggil dari Kotlin, dan tiap
panggilan membawa salinan payload lewat JNI. 10×/detik sudah tidak terasa
bedanya untuk teks subtitle yang berganti tiap 2–4 detik.

## Batas payload

Cue dipangkas ke 30 terakhir **di sisi JS**, sebelum di-stringify:

```js
if (Array.isArray(p.cues) && p.cues.length > 30) {
  p.truncatedFrom = p.cueCount - 30;
  p.cues = p.cues.slice(-30);
}
```

Kalau tidak dipangkas, polling akan ikut membawa seluruh rekaman. Pada menit
ke-90 itu sekitar 1500 cue / 200 KB per polling, sia-sia karena panel hanya
menampilkan 30 baris. Jumlah yang dipangkas dikembalikan lewat
`truncatedFrom`, dipakai panel untuk menulis "… N cue sebelumnya".

Ekspor **tidak** memakai payload yang dipangkas: tombol ekspor membaca
`READ_FULL`. Kalau ekspor ikut memakai yang dipangkas, file SRT untuk episode
panjang akan berisi 30 cue terakhir saja tanpa peringatan apa pun.

---

## Ekspor

| Tombol | Keluaran |
|---|---|
| Simpan JSON | `<judul episode>.json` — JSON mentah dari halaman, apa adanya |
| Simpan SRT | `<judul episode>.srt` |

Nama berkas diambil dari judul episode, dipotong pada pemisah `｜` dan
dibersihkan dari karakter terlarang oleh `FileNames` (maks 80 karakter):

```
Running Man (2024) Episode 733｜Variety Show Korea｜Viu Indonesia
  -> Running Man (2024) Episode 733.srt
```

Kalau judul tidak terbaca, dipakai nama cadangan `viu-sub_<posisi>-of-<durasi>`
supaya berkas tidak pernah bernama kosong.

SRT disusun di `SrtWriter` dan sudah pernah dibandingkan byte-identic dengan
file dari userscript. Cue yang masih berjalan saat ekspor (`pendingCues`)
ikut ditulis, asal posisinya memang setelah cue sebelumnya.

JSON disimpan apa adanya, bukan dimodelkan ulang di Kotlin, supaya tidak ada
field yang hilang.

Folder dicek lebih dulu saat menyimpan: kalau belum pernah dipilih, pemilih
folder langsung terbuka dan ekspor dilanjutkan otomatis setelah folder
ditentukan (tidak perlu menekan Simpan dua kali). Setiap ekspor memunculkan
notifikasi kecil beranimasi di atas bar — "Menyimpan JSON…" saat mulai, lalu
"Tersimpan: nama berkas" atau pesan galat berwarna merah saat selesai.

Penggunaan: sekali tekan **Pilih Folder**, arahkan ke `Documents`, izin
baca+tulis disimpan permanen. Setelah itu tombol ekspor langsung menulis ke
folder itu tanpa meminta lagi. Ini Storage Access Framework, bukan permission
storage.

---

## Perekam

### Cara memindai

`querySelectorAll('span,div,p,em,b,strong')` di seluruh dokumen, lalu
disaring. Setiap scan menghasilkan daftar kandidat, diurutkan dari yang paling
atas, lalu digabung jadi cue.

### Filter kandidat

| Filter | Syarat |
|---|---|
| `bmpui` | class-nya mengandung `bmpui` (kontrol UI Viu) |
| `leaf` | punya anak elemen — hanya elemen daun yang diambil |
| `empty` | teks kosong, atau panjangnya lebih dari 180 karakter |
| `symbol` | **seluruh** teksnya simbol (ikon) |
| `timecode` | pola `mm:ss` atau `hh:mm:ss` |
| `closest` | berada di dalam container bermenupunah (quality, menu, option, caption, episode, list) |
| `geometry` | terlalu kecil, terlalu tinggi, selebar video, atau berada di luar area video |

Penghitung penolakan ikut dikirim dalam payload dan ditampilkan di panel
saat panel dibuka, supaya bisa diketahui filter mana yang membuang subtitle
daripada menebak.

Satu perbaikan penting: filter `symbol` versi lama membuang baris yang
sekadar **mengandung** simbol, sehingga baris lagu ber-notasi musik ikut
hilang. Sekarang hanya baris yang seluruhnya simbol yang dibuang.

### Penyimpanan

**Tidak ada `localStorage` sama sekali.** Setiap episode dibuka dengan
rekaman bersih — bukan melanjutkan sesi sebelumnya.

Konsekuensinya: kalau halaman dimuat ulang atau app dimatikan di tengah
rekaman, sesi itu hilang. Ini keputusan yang disengaja, demi episode yang
baru dibuka tidak ikut memuat cue episode lain.

---

## Yang sengaja dibiarkan

**Baris subtitle Korea (Hangul) tidak ikut terekam.** Viu merender baris
Korea dengan pecahan warna dalam beberapa elemen anak, jadi induknya punya
`firstElementChild` dan dilewati oleh filter "hanya elemen daun". Baris
Indonesia biasanya satu elemen utuh, jadi lolos.

Ini perilaku userscript v5.1 yang asli, bukan balikan dari app ini. Sudah
diputuskan tidak diperbaiki: yang dipakai subtitle terjemahan Indonesia, dan
melonggarkan filter elemen daun berisiko ikut menangkap container sehingga
subtitle kacau.

Kalau suatu saat baris Korea memang dibutuhkan, jangan mengubah filter
`if (el.firstElementChild) continue;` secara langsung.

---

## Kenapa JS tetap ada

Perekam ini memindai DOM viu.com: `querySelectorAll('span')`,
`getBoundingClientRect()`, posisi video. Kode native Android tidak bisa
menyentuh DOM halaman web. JS tidak bisa dihilangkan — tapi tidak harus berupa
userscript, dan memang sekarang bukan.

## Akses, bukan bridge

App tidak memakai `addJavascriptInterface`, jadi tidak ada objek native yang
terekspos ke halaman web. App memanggil `evaluateJavascript` dan membaca
hasilnya sendiri.

---

## Batasan yang diketahui

- **Rekaman hilang saat halaman dimuat ulang.** Tidak ada autosave. Ini
  konsekuensi langsung dari penghapusan `localStorage`.
- **Fullscreen native hanya menampilkan permukaan video.** Kalau overlay
  subtitle Viu berada di luar elemen `<video>`, subtitle tidak ikut terlihat
  di layar penuh. Rekaman tetap jalan karena `scan()` membaca DOM, bukan
  piksel — tapi kalau cue berhenti bertambah di layar penuh, itu penyebabnya.
- **Baris subtitle Korea tidak terekam.** Sudah dibahas di atas.
- **Panjang rekaman dibatasi RAM halaman, bukan app.** Semua cue ditahan di
  `window` sebagai array JavaScript selama episode berjalan. Pada episode 90
  menit itu wajar, tapi untuk film 2 jam array akan terus membesar.
- **Tidak ada ekspor tanpa interaksi pengguna.** Semua penulisan lewat SAF,
  jadi folder harus dipilih sekali. Tidak ada path langsung ke `Documents`.
- **`X-Requested-With` tidak dihapus.** WebView tetap mengirim header berisi
  `dev.ifan.viusub`.
- **Belum ada pengujian otomatis.** Struktur APK diverifikasi statis
  (`aapt2 dump xmltree`), dan logika JS dibandingkan fungsi per fungsi dengan
  aslinya, tapi tidak ada test suite.

---

## Struktur berkas

```
app/src/main/
├── AndroidManifest.xml
├── java/dev/ifan/viusub/
│   ├── Cari.kt                 aturan pencarian (port rout-web)
│   ├── ChromeUserAgent.kt      pembersihan User-Agent WebView
│   ├── Episode.kt              model episode
│   ├── EpisodeAdapter.kt       adapter RecyclerView + sorotan
│   ├── EpisodeCache.kt         cache daftar di filesDir
│   ├── ExportStore.kt          penulisan file lewat SAF
│   ├── FileNames.kt            pembersih nama berkas
│   ├── HomeActivity.kt         layar Home
│   ├── MainActivity.kt         layar pemutar + ekspor + fullscreen
│   ├── PayloadFormatter.kt     format teks panel
│   ├── PlayerFocusScript.kt    skrip "hanya pemain", sebagai Kotlin constant
│   ├── RecorderScript.kt       kode JS perekam, sebagai Kotlin constant
│   ├── SrtWriter.kt            penyusun file .srt
│   ├── StickyYearDecoration.kt header tahun lengket
│   ├── ThumbnailLoader.kt      pemuat thumbnail + LruCache
│   ├── ViuApi.kt               pengambilan daftar dari API Viu
│   └── VtrPayload.kt           model data payload
└── res/
    ├── drawable/               bg_*, ic_*
    ├── layout/                 activity_home, activity_main, item_episode, item_year
    └── values/                 colors, strings, styles, themes
```

### Sumber data

```
https://api-gateway-global.viu.com/api/mobile
  ?r=/vod/product-list&os_flag_id=1&series_id=<id>&size=-1&sort=desc
```

Prefix `/api/` di depan `mobile` mudah terlewat — tanpa itu semua permintaan
jawab 404. Satu permintaan per musim, digabung:

| Musim | `series_id` | Slug |
|---|---|---|
| 2024 | `72839` | `Running-Man-2024` |
| 2025 | `85365` | `Running-Man-2025` |
| 2026 | `97518` | `Running-Man-2026` |

Musim yang gagal diambil dilewati, bukan menggagalkan semuanya: lebih baik
menampilkan dua musim daripada layar kosong.

---

## Build

```sh
. ~/IfanID/androidenv.sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

```sh
cp app/build/outputs/apk/debug/app-debug.apk /storage/emulated/0/Documents/
```

## Catatan environment

| File | Isi penting |
|---|---|
| `local.properties` | `sdk.dir=/home/ifan/android-sdk` — bukan `$HOME/...`, JVM tidak me-resolve `/data/data/com.termux/...` |
| `gradle.properties` | `android.aapt2FromMavenOverride` — **wajib** di scope project, hanya di `~/.gradle/` tidak cukup |
| `app/build.gradle.kts` | `packaging { jniLibs { useLegacyPackaging = true } }` — `zipalign` bawaan build-tools adalah binary x86_64 |

Plugin `org.jetbrains.kotlin.android` **tidak boleh** dipasang. AGP 9.x
sudah membawa dukungan Kotlin bawaan dan build akan gagal kalau plugin itu
ditambahkan.

`androidx.core:core-ktx` sengaja ditahan di `1.17.0`. Versi `1.19.0`
memaksa `compileSdk` 37, yang berarti memakai platform preview `android-37.0`.

`adb` dari SDK build-tools tidak bisa dijalankan di Termux (binary x86_64).
Gunakan `aapt2` dari Termux untuk memeriksa isi APK.

## Stack

| Komponen | Versi |
|---|---|
| AGP | 9.3.2 |
| Gradle | 9.5.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 31 |
| Kotlin | bawaan AGP 9 |
| UI | XML Views + ViewBinding |
| Izin aplikasi | `INTERNET`, `ACCESS_NETWORK_STATE` saja |
