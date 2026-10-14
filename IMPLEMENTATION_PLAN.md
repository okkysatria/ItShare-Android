# Rencana Implementasi (Implementation Task Plan): QR SMB Share Client (Android)

Berdasarkan PRD Versi 0.2, dokumen ini menyusun rencana implementasi komprehensif, terstruktur per fase/milestone (M1 - M5), arsitektur modular, checklist teknis, pembagian task, serta mitigasi risiko.

---

## 1. Arsitektur & Tech Stack

- **Platform Target:** Min SDK 26 (Android 8.0) - Target SDK 34/35 (Android 14/15)
- **UI Framework:** Jetpack Compose + Material 3 + Jetpack Navigation Compose
- **Architecture Pattern:** Clean Architecture + MVVM / MVI (Unidirectional Data Flow)
- **Dependency Injection:** Hilt / Koin
- **Asynchronous / Concurrency:** Kotlin Coroutines + Flow
- **Network / SMB Engine:** `com.hierynomus:smbj` (SMB2/3 client)
- **Kamera & QR Scanner:** CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) + Google ML Kit Barcode Scanning
- **WiFi Connection:** `WifiNetworkSpecifier` / `ConnectivityManager` (Android 10+) dan legacy `WifiManager` (Android 8-9)
- **Storage / File Access:** Storage Access Framework (SAF) (`ActivityResultContracts.OpenDocumentTree`, `CreateDocument`, `OpenDocument`)
- **Background Tasks:** Android Foreground Service (dengan `ServiceCompat` + `Ongoing Notification`)
- **Penyimpanan Lokal & Keamanan:** 
  - Room Database / DataStore Preferences
  - Android Keystore + EncryptedSharedPreferences / Tink (kredensial sesi permanen)
- **Serialization:** `kotlinx.serialization` (JSON parsing QR SMB, WiFi, dan Backup/Restore)

---

## 2. Struktur Modul & Paket (Modular/Clean Architecture)

```
app/
 ├── core/
 │    ├── common/          # Dispatchers, Result wrapper, Extensions, Date/Time utils
 │    ├── network/smb/     # SMBJ wrapper, connection pool, session manager, stream wrappers
 │    ├── wifi/            # WifiConnector (NetworkSpecifier & legacy WifiManager)
 │    ├── qr/              # ML Kit scanner analyzer & QR Parsers (SMB & WiFi parser/unescape)
 │    ├── security/        # Android Keystore crypto manager
 │    └── storage/         # Storage Access Framework (SAF) tree/document helpers
 ├── data/
 │    ├── model/           # Data entity & QR contract models
 │    ├── repository/      # SessionRepository, TransferRepository, SettingsRepository
 │    └── local/           # Room DB (History, Preferences), Backup/Restore Json Serializer
 ├── domain/
 │    └── usecase/         # ConnectSmbUseCase, DownloadRecursiveUseCase, UploadUseCase, etc.
 ├── service/
 │    └── transfer/        # TransferForegroundService, NotificationHelper, TransferQueue
 └── ui/
      ├── onboarding/      # Layar Awal (Dual Choice: Scan WiFi vs Scan Folder Share)
      ├── scanner/         # CameraX View, Overlay reticle, ScannerViewModel
      ├── browser/         # File Explorer (Breadcrumbs, List/Grid, Multi-select, Context Menu)
      ├── transfer/        # Transfer Progress Monitor (Speed, Progress bar, Cancel button)
      ├── history/         # Sesi terakhir, reconnect, kelola bookmark
      ├── settings/        # Backup & Import JSON, Pengaturan tema & bahasa
      └── theme/           # Color, Typography, Material3 Theme
```

---

## 3. Rincian Breakdown Task Implementasi

### Phase 1: Setup Proyek, Core Engine & Kontrak Data (Milestone 1 - Bagian A)
> **Fokus:** Inisialisasi Gradle, dependensi, parser payload QR SMB/WiFi, dan SMB engine wrapper.

- [x] **Task 1.1: Project Initialization & Dependency Setup**
  - Setup Kotlin DSL, Compose BOM, Material3, Navigation Compose, Coroutines.
  - Setup dependensi SMBJ (`com.hierynomus:smbj`), CameraX, ML Kit Barcode, DataStore/Keystore, DocumentFile.
  - Konfigurasi ProGuard/R8 rules khusus untuk SMBJ dan BouncyCastle/ASN1.
- [x] **Task 1.2: Payload Parser & Validator (Contract Implementation)**
  - Model data `SmbPayload` (`v`, `host`, `port`, `share`, `user`, `pass`, `ro`, `exp`).
  - Unit test validasi: Versi payload, kelengkapan field, validasi rentang port, cek timestamp `exp` dengan toleransi jam HP.
  - Model data & parser `WifiPayload` (`WIFI:T:...;S:...;P:...;;`) dengan implementasi *unescaping* karakter khusus (`;`, `,`, `:`, `\`).
- [x] **Task 1.3: SMB Client Wrapper (`SmbManager`)**
  - Buat wrapper SMBJ: inisialisasi `SMBClient`, `Connection`, `Session`, dan `DiskShare`.
  - Dukungan kustom port (`port` dari payload).
  - Implementasi safe close / connection teardown saat session disconnect.
  - Listing direktori rekursif dan pembuatan direktori remote.

---

### Phase 2: QR Scanner, WiFi Connector, & Layar Awal (Milestone 1 - Bagian B)
> **Fokus:** UI Onboarding, integrasi CameraX + ML Kit, dan integrasi WiFi Android API.

- [x] **Task 2.1: Layar Awal Pemakaian Pertama (FR-AN-12, FR-AN-17)**
  - UI 2 tombol besar: "Scan QR WiFi" dan "Scan QR Folder Share".
  - Logika peralihan: jika sudah ada riwayat sesi, tampilkan daftar sesi terakhir untuk quick-reconnect.
- [x] **Task 2.2: CameraX & ML Kit Barcode Scanner Component (FR-AN-01)**
  - Request runtime permission kamera dengan fallback dialog ramah jika ditolak.
  - `ImageAnalysis.Analyzer` dengan ML Kit Barcode Scanner.
  - Visual scanning: reticle frame, overlay preview.
- [x] **Task 2.3: WiFi Connection Manager (FR-AN-13)**
  - Implementasi `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork` untuk Android 10+ (API 29+).
  - Implementasi legacy `WifiManager.addNetwork` / `enableNetwork` untuk Android 8-9 (API 26-28).
  - Alur UX: setelah sukses terkoneksi WiFi, otomatis tampilkan prompt/navigasi ke "Scan QR Folder Share".
- [x] **Task 2.4: Input Manual Sesi SMB (FR-AN-11)**
  - Form dialog cadangan (Host, Port, Share, Username, Password, Read-Only toggle) jika kamera tidak berfungsi atau izin ditolak permanen.

---

### Phase 3: File Browser & Navigasi SMB (Milestone 1 - Bagian C)
> **Fokus:** Autentikasi transparan, browsing folder, paging, dan navigasi file.

- [x] **Task 3.1: Silent Authentication & Connect Transition (FR-AN-03, FR-AN-14)**
  - Transisi dari Scanner langsung memicu koneksi tanpa form login.
  - Loading state visual dengan toast feedback.
  - Error state komprehensif: Host tidak terjangkau, kredensial ditolak, atau expired (FR-AN-07).
- [x] **Task 3.2: Remote File Browser UI & ViewModel (FR-AN-04)**
  - Tampilan direktori: Breadcrumb navigation interaktif, tombol up-directory, pull-to-refresh/reload.
  - Item list: Ikon tipe file khusus (gambar, video, audio, pdf, zip, teks), nama, ukuran terformat, tanggal modifikasi.
  - Pencarian / filter berkas real-time.
  - Fitur Pilih Semua (Select All) / Batal Pilih.
  - Dialog informasi detail berkas.
  - Logika Read-Only (`ro: true`): Disable tombol Upload.

---

### Phase 4: Download, Upload, dan Transfer Service (Milestone 2 - Bagian A)
> **Fokus:** Foreground Service, SAF integration, recursive transfer, dan conflict handling.

- [x] **Task 4.1: Transfer Foreground Service & Notification (FR-AN-10)**
  - Foreground Service dengan notifikasi persisten (`Ongoing Notification`) menampilkan progres total, kecepatan (MB/s), file aktif, dan aksi "Batal".
  - Mekanisme `WakeLock` agar transfer tidak putus saat layar mati.
- [x] **Task 4.2: Engine Download via Storage Access Framework (SAF) (FR-AN-05)**
  - Integrasi SAF folder picker (`OpenDocumentTree`) untuk target download.
  - Download single file dan folder rekursif melalui SMB stream ke `DocumentFile` / `ContentResolver.openOutputStream`.
  - Kalkulasi progres real-time, throughput kecepatan (MB/s).
- [x] **Task 4.3: Engine Upload ke SMB (FR-AN-06)**
  - Integrasi SAF file picker (`OpenMultipleDocuments`).
  - Streaming upload dari URI HP ke SMBJ `File.getOutputStream()`.
  - Pengecekan status `ro == true` sebelum memulai upload.
- [x] **Task 4.4: Penanganan Konflik Nama & File Parsial (FR-AN-08, FR-AN-14)**
  - Deteksi file sudah ada di target (baik download di HP maupun upload di SMB).
  - Resolusi konflik: Overwrite, Skip, atau Auto-Rename (`name (1).ext`).
- [x] **Task 4.5: Transfer Monitor Screen & Banner (US-09, Seksi 13)**
  - Banner progres aktif di penjelajah file menampilkan file aktif, progress bar, persentase, kecepatan (MB/s), dan tombol Batal.

---

### Phase 5: Keamanan, Riwayat Sesi, & Backup/Restore (Milestone 2 - Bagian B)
> **Fokus:** Keystore encryption, persistence, dan JSON Backup/Import via SAF.

- [x] **Task 5.1: Session History & Keystore Security (FR-AN-09, Seksi 11)**
  - Penyimpanan riwayat sesi permanen memakai Android Keystore + EncryptedSharedPreferences.
  - Layar Utama (FR-AN-17): Daftar sesi terakhir, quick-reconnect untuk sesi tersimpan yang valid, dan hapus riwayat.
- [x] **Task 5.2: Backup Data ke File JSON via SAF (FR-AN-15)**
  - Serializer JSON untuk riwayat sesi dan preferensi pengguna.
  - **Aturan Keamanan:** Password dan kredensial sensitif dilarang keras ikut diekspor.
  - Simpan file menggunakan SAF `CreateDocument` (`application/json`).
- [x] **Task 5.3: Import Data dari File JSON via SAF (FR-AN-16)**
  - Baca file melalui SAF `OpenDocument`.
  - Validasi skema, integritas format, dan versi cadangan (tolak file korup).
  - Penggabungan data riwayat dengan deduplikasi otomatis.

---

### Phase 6: Pengujian, Optimasi, & Stabilisasi (Milestone 4 & 5)
> **Fokus:** Performance benchmark, stress test, edge case, dan rilis APK.

- [ ] **Task 6.1: Unit & Parser Testing (Seksi 16)**
  - Unit test parsing QR JSON SMB (variasi payload normal, expired, port kustom, karakter aneh).
  - Unit test parsing QR WiFi (unescaping `\;`, `\,`, `\:`, `\\`).
  - Unit test serialisasi/deserialisasi Backup & Restore JSON.
- [ ] **Task 6.2: Integration & Stress Testing**
  - Uji transfer file tunggal 4 GB melalui WiFi 5 GHz (target throughput >= 20 MB/s).
  - Uji folder rekursif dengan ribuan file kecil.
  - Uji ketahanan: WiFi putus tiba-tiba, mode hemat baterai (Doze mode), layar mati.
  - Uji kompatibilitas multi-versi Android: Android 8 (API 26), Android 11 (API 30), Android 14/15.
- [ ] **Task 6.3: Polish UI/UX, Lokalisasi & Reduksi Ukuran APK**
  - String resources multibahasa: Bahasa Indonesia & English.
  - Dark mode / Material You theming.
  - ProGuard/R8 shrinking & asset optimization untuk memastikan ukuran APK < 20 MB.
  - Audit aksesibilitas (kontras warna, content descriptions TalkBack).

---

## 4. Estimasi Timeline & Dependensi

| Milestone | Durasi | Dependensi | Deliverable Utama |
|---|---|---|---|
| **M1** | 2 Minggu | Selesai M0 di Host PC (QR Generator tersedia) | Scanner QR WiFi & SMB, koneksi SMBJ, navigasi direktori. |
| **M2** | 2 Minggu | M1 Selesai | Download/Upload SAF rekursif, Foreground Service, Backup/Import JSON. |
| **M4** | 2 Minggu | M2 Selesai, Host PC M3 selesai | Testing beban (4 GB & 10k file), testing Android 8/11/14, audit keamanan. |
| **M5** | 2 Minggu | M4 Selesai | Beta tertutup, hardening ProGuard (<20 MB), rilis v1.0. |

---

## 5. Rencana Pengujian & Matriks Validasi

| Skenario Uji | Kriteria Sukses |
|---|---|
| **Scan to File List** | < 3 detik dari deteksi QR hingga daftar root folder tampil. |
| **WiFi QR Connection** | Berhasil connect ke SSID tanpa input password manual (dialog persetujuan di Android 10+). |
| **Transfer 4 GB** | Selesai tanpa OOM, memory leak, atau pemutusan oleh OS di latar belakang. |
| **Kecepatan Transfer** | Rata-rata >= 20 MB/s pada WiFi 5 GHz. |
| **Sesi Expired** | Ditolak otomatis jika `exp` terlampaui; pesan kesalahan ramah pengguna. |
| **Read-Only Enforce** | Tombol Upload disabled jika payload memiliki `ro: true`. |
| **Backup Sanitization** | File JSON hasil backup diverifikasi tidak mengandung data password. |
