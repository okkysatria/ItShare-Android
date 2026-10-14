# ItShare (Android Client)

[![Download ItShare APK](https://img.shields.io/badge/Download-ItShare_v1.0.0_(Android_APK)-34A853?style=for-the-badge&logo=android&logoColor=white)](https://github.com/okkysatria/ItShare-Android/releases/download/v1.0.0/ItShare.apk)
[![GitHub Release](https://img.shields.io/github/v/release/okkysatria/ItShare-Android?style=for-the-badge&color=blue)](https://github.com/okkysatria/ItShare-Android/releases/latest)
[![PC Host](https://img.shields.io/badge/PC_Host-Repository-2ea44f?style=for-the-badge&logo=windows&logoColor=white)](https://github.com/okkysatria/ItShare-pc)

> **Instant Download**: [📥 **ItShare.apk (v1.0.0)**](https://github.com/okkysatria/ItShare-Android/releases/download/v1.0.0/ItShare.apk) — Direct APK package for Android 8.0+ devices.

An Android client designed to connect to the **ItShare PC** host via local SMB (Server Message Block). It allows browsing remote directories, previewing media files inline, and transferring files at local network speeds without third-party cloud infrastructure.

The client relies on standard SMB2/SMB3 dialects provided by `com.hierynomus:smbj`.

---

## Technical Highlights

- **CameraX + ML Kit Scanning**: Zero-latency QR code detection with fallback manual address input dialog.
- **SMBJ Client Integration**: Uses SMB2/SMB3 over TCP (port `445` or custom ports such as `4455`). Implements custom chunked stream buffering to prevent memory pressure during large file transfers.
- **EXIF-Aware Thumbnail Pipeline**: Reads image headers over SMB and normalizes orientation using Android's `ExifInterface` and `Matrix.postRotate` prior to Bitmap decoding.
- **Remote Video Frame Extraction**: Samples remote video streams using `MediaMetadataRetriever` to render accurate file preview thumbnails without downloading full video payloads.
- **Inline Media Previewing**:
  - Full-resolution photo viewer dialog with pinch-to-zoom capabilities.
  - Video playback using `MediaPlayer` backed by a synchronized `TextureView` to maintain source aspect ratios with tap-to-pause controls.
- **Background Transfer Engine**:
  - Implements an Android `ForegroundService` (`dataSync` type) with a partial `WakeLock` to prevent process termination by system Doze modes.
  - Real-time speed calculation (`MB/s` / `KB/s`), progress percentage updates, and explicit user-cancellation signals.
  - Conflict resolution strategies: *Overwrite*, *Skip*, or *Keep Both*.
- **Hardware-Backed Session Storage**: Permanent share connections are saved using `androidx.security.crypto.EncryptedSharedPreferences` backed by Android Keystore (`AES256_GCM`).

---

## Technical Specifications

- **Minimum SDK**: Android 8.0 (API Level 26)
- **Target SDK**: Android 15 (API Level 35)
- **Compile SDK**: 37
- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose, Material 3
- **Network Stack**: SMBJ 0.15.0, Okio, Java NIO

---

## Getting Started

### Prerequisites

- Android Studio Koala Feature Drop / Ladybug (2024.1.2+) or later.
- JDK 17 or JDK 21 (configured as Gradle JDK).
- Android device or emulator running API 26 or higher.

### Building from Source

Clone the repository and build via Gradle:

```bash
git clone https://github.com/your-username/ItShare-Android.git
cd ItShare-Android

# Build Debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Runtime Permissions

The application requests the following Android permissions as needed:

| Permission | Purpose |
|---|---|
| `CAMERA` | Required to scan host QR codes via ML Kit Barcode API. |
| `INTERNET` & `ACCESS_NETWORK_STATE` | Required for raw TCP socket communication with the SMB server. |
| `ACCESS_WIFI_STATE` & `CHANGE_WIFI_STATE` | Required to associate with host PC Wi-Fi hotspots directly. |
| `ACCESS_FINE_LOCATION` | Required by Android OS (Android 10+) to scan for and connect to Wi-Fi SSIDs. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC` | Keeps large file transfers alive when the app is in the background. |
| `POST_NOTIFICATIONS` | Required on Android 13+ to display ongoing transfer notifications. |

---

## Project Structure

```text
ItShare-Android/
├── app/
│   ├── build.gradle.kts     # Module-level Gradle configuration & dependencies
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/itshare/client/
│       │   │   ├── core/
│       │   │   │   ├── backup/        # Session export and import logic
│       │   │   │   ├── model/         # SmbFileItem, QR payloads, transfer models
│       │   │   │   ├── qr/            # JSON schema validation and parsing
│       │   │   │   ├── security/      # EncryptedSharedPreferences wrapper
│       │   │   │   ├── service/       # TransferForegroundService implementation
│       │   │   │   ├── smb/           # SmbClientManager & connection pooling
│       │   │   │   ├── transfer/      # TransferManager, chunking, and speed meters
│       │   │   │   └── wifi/          # WifiNetworkSpecifier auto-connect handler
│       │   │   ├── ui/
│       │   │   │   ├── browser/       # FileBrowserScreen, MediaPreviewDialog, SmbMediaLoader
│       │   │   │   ├── onboarding/    # Main navigation tabs, history, settings screen
│       │   │   │   ├── scanner/       # CameraX preview and manual IP entry modal
│       │   │   │   └── theme/         # Color schemes, typography, and shape definitions
│       │   │   ├── MainActivity.kt    # Root Compose host activity
│       │   │   └── ItShareApp.kt      # Application entrypoint
│       │   └── res/                   # Drawables, strings, and XML configurations
│       └── test/                      # Unit tests (QR parser, backup serializations)
├── gradle/                  # Gradle wrapper binary and configuration
├── build.gradle.kts         # Root build script
├── settings.gradle.kts      # Project naming and repository resolutions
└── README.md
```

---

## License

This project is distributed under the MIT License.
