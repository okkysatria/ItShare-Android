package com.itshare.client

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import com.itshare.client.core.backup.BackupManager
import com.itshare.client.core.backup.SessionHistoryItem
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.model.SmbPayload
import com.itshare.client.core.model.ConflictResolution
import com.itshare.client.core.model.WifiPayload
import com.itshare.client.core.qr.QrParser
import com.itshare.client.core.security.SecureSessionStore
import com.itshare.client.core.smb.SmbClientManager
import com.itshare.client.core.transfer.TransferManager
import com.itshare.client.core.wifi.WifiConnector
import com.itshare.client.ui.browser.FileBrowserScreen
import com.itshare.client.ui.browser.loadSmbFullImage
import com.itshare.client.ui.browser.loadSmbThumbnail
import com.itshare.client.ui.onboarding.OnboardingScreen
import com.itshare.client.ui.onboarding.DashboardTab
import com.itshare.client.ui.scanner.ManualInputDialog
import com.itshare.client.ui.scanner.QrScannerScreen
import com.itshare.client.ui.theme.ItShareTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

sealed class AppDestination {
    object Onboarding : AppDestination()
    object Scanner : AppDestination()
    object Browser : AppDestination()
}

class MainActivity : ComponentActivity() {

    private val smbManager = SmbClientManager()
    private lateinit var wifiConnector: WifiConnector
    private lateinit var sessionStore: SecureSessionStore
    private lateinit var transferManager: TransferManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiConnector = WifiConnector(this)
        sessionStore = SecureSessionStore(this)
        transferManager = TransferManager(this, smbManager)

        setContent {
            val uiPreferences = remember {
                getSharedPreferences("itshare_ui", MODE_PRIVATE)
            }
            var themeMode by remember {
                mutableStateOf(uiPreferences.getString("theme_mode", "system") ?: "system")
            }
            var language by remember {
                mutableStateOf(uiPreferences.getString("language", "en") ?: "en")
            }
            var exploreViewMode by remember {
                mutableStateOf(uiPreferences.getString("explore_view_mode", "list") ?: "list")
            }
            var folderSortMode by remember {
                mutableStateOf(uiPreferences.getString("folder_sort_mode", "recent") ?: "recent")
            }
            var downloadFolderUri by remember {
                mutableStateOf(uiPreferences.getString("download_folder_uri", null)?.let(Uri::parse))
            }
            val useDarkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ItShareTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentDestination by remember { mutableStateOf<AppDestination>(AppDestination.Onboarding) }
                    var scannerResetKey by remember { mutableIntStateOf(0) }
                    var dashboardTab by remember { mutableStateOf(DashboardTab.FOLDERS) }
                    var currentPath by remember { mutableStateOf("") }
                    var currentShareName by remember { mutableStateOf("") }
                    var files by remember { mutableStateOf(emptyList<SmbFileItem>()) }
                    var isLoading by remember { mutableStateOf(false) }
                    var isConnecting by remember { mutableStateOf(false) }
                    var directoryLoadJob by remember { mutableStateOf<Job?>(null) }
                    var isReadOnly by remember { mutableStateOf(false) }
                    var showManualInputDialog by remember { mutableStateOf(false) }
                    var manualInputSeed by remember { mutableStateOf<SmbPayload?>(null) }
                    var smbConnectError by remember { mutableStateOf<String?>(null) }
                    var savedSessions by remember { mutableStateOf(sessionStore.getAllSavedSessions()) }
                    var pendingDownloadFiles by remember { mutableStateOf<List<SmbFileItem>>(emptyList()) }
                    var pendingUploadUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
                    var uploadConflictNames by remember { mutableStateOf<List<String>>(emptyList()) }
                    var showUploadConflictDialog by remember { mutableStateOf(false) }
                    var pendingWifiPayload by remember { mutableStateOf<WifiPayload?>(null) }

                    val transferProgress by transferManager.progressState.collectAsState()

                    fun startDownload(filesToDownload: List<SmbFileItem>, targetUri: Uri) {
                        lifecycleScope.launch {
                            val result = transferManager.downloadFiles(filesToDownload, targetUri)
                            result.onSuccess { count ->
                                val msg = if (language == "id") {
                                    "Berhasil mengunduh $count berkas"
                                } else {
                                    "Downloaded $count files"
                                }
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }.onFailure { error ->
                                Log.e("ItShareTransfer", "Download failed", error)
                                val msg = if (language == "id") {
                                    "Gagal mengunduh: ${error.localizedMessage}"
                                } else {
                                    "Download failed: ${error.localizedMessage}"
                                }
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    fun loadDirectory(path: String) {
                        directoryLoadJob?.cancel()
                        isLoading = true
                        val normalizedPath = path.replace('\\', '/').trim('/')
                        Log.d("ItShareBrowser", "Loading directory '${normalizedPath.ifBlank { "." }}'")
                        directoryLoadJob = lifecycleScope.launch {
                            val result = smbManager.listFiles(normalizedPath)
                            if (!isActive) return@launch
                            isLoading = false
                            result.onSuccess {
                                currentPath = normalizedPath
                                files = it
                                Log.i("ItShareBrowser", "Loaded ${it.size} entries from '${normalizedPath.ifBlank { "." }}'")
                            }.onFailure { err ->
                                Log.e("ItShareBrowser", "Could not open directory '$normalizedPath'", err)
                                Toast.makeText(
                                    this@MainActivity,
                                    "Gagal memuat folder: ${err.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    fun connectToSmb(payload: SmbPayload) {
                        if (transferManager.isTransferRunning()) {
                            Toast.makeText(
                                this@MainActivity,
                                if (language == "id") "Selesaikan atau batalkan transfer sebelum mengganti koneksi." else "Finish or cancel the transfer before changing connections.",
                                Toast.LENGTH_LONG
                            ).show()
                            return
                        }
                        if (isConnecting) return
                        isConnecting = true
                        Log.i("ItShareSMB", "Connecting: host=${payload.host}, port=${payload.port}, share=${payload.share}, readOnly=${payload.readOnly}")
                        isLoading = true
                        lifecycleScope.launch {
                            val result = smbManager.connect(payload)
                            isConnecting = false
                            isLoading = false
                            result.onSuccess {
                                Log.i("ItShareSMB", "Connected and authenticated to ${payload.host}:${payload.port}/${payload.share}")
                                isReadOnly = payload.readOnly
                                currentShareName = payload.share
                                sessionStore.savePermanentSession(payload)
                                savedSessions = sessionStore.getAllSavedSessions()
                                currentDestination = AppDestination.Browser
                                loadDirectory("")
                            }.onFailure { err ->
                                Log.e("ItShareSMB", "Connection failed for ${payload.host}:${payload.port}/${payload.share}", err)
                                val message = err.localizedMessage ?: "Login SMB ditolak."
                                if (currentDestination is AppDestination.Scanner) {

                                    smbConnectError = message
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Koneksi gagal: $message",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }

                    fun uploadSelectedFiles(uris: List<Uri>, conflict: ConflictResolution) {
                        showUploadConflictDialog = false
                        pendingUploadUris = emptyList()
                        if (transferManager.isTransferRunning()) {
                            Toast.makeText(
                                this@MainActivity,
                                if (language == "id") "Transfer lain masih berjalan." else "Another transfer is still running.",
                                Toast.LENGTH_LONG
                            ).show()
                            return
                        }
                        lifecycleScope.launch {
                            Toast.makeText(
                                this@MainActivity,
                                "Memulai unggah ${uris.size} berkas...",
                                Toast.LENGTH_SHORT
                            ).show()

                            val result = transferManager.uploadFiles(uris, currentPath, conflict)
                            result.onSuccess { count ->
                                Toast.makeText(
                                    this@MainActivity,
                                    "Berhasil mengunggah $count berkas ke PC!",
                                    Toast.LENGTH_LONG
                                ).show()
                                loadDirectory(currentPath)
                            }.onFailure { err ->
                                Log.e("ItShareTransfer", "Upload gagal ke $currentPath", err)
                                Toast.makeText(
                                    this@MainActivity,
                                    "Gagal unggah: ${err.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    fun connectToWifi(payload: WifiPayload) {
                        lifecycleScope.launch {
                            val connectingText = if (language == "id") "Menghubungkan ke Wi-Fi ${payload.ssid}..." else "Connecting to Wi-Fi ${payload.ssid}..."
                            Toast.makeText(this@MainActivity, connectingText, Toast.LENGTH_SHORT).show()
                            runCatching { wifiConnector.connect(payload).getOrThrow() }
                                .onSuccess {
                                    Toast.makeText(this@MainActivity, "Wi-Fi tersambung. Pindai QR SMB untuk membuka folder.", Toast.LENGTH_LONG).show()
                                    scannerResetKey++
                                }
                                .onFailure { error ->
                                    Log.e("ItShareScan", "Wi-Fi connection failed", error)
                                    Toast.makeText(this@MainActivity, "Gagal sambung Wi-Fi: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                                    scannerResetKey++
                                }
                        }
                    }

                    val wifiPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val payload = pendingWifiPayload
                        pendingWifiPayload = null
                        val requiredPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions[Manifest.permission.NEARBY_WIFI_DEVICES] == true
                        } else {
                            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                        }
                        if (requiredPermissionGranted && payload != null) {
                            connectToWifi(payload)
                        } else {
                            Toast.makeText(this@MainActivity, "Izin Wi-Fi diperlukan untuk menyambungkan jaringan dari QR.", Toast.LENGTH_LONG).show()
                            scannerResetKey++
                        }
                    }

                    val pickDownloadFolderLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        if (uri != null) {
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                            }
                            downloadFolderUri = uri
                            uiPreferences.edit { putString("download_folder_uri", uri.toString()) }
                            if (pendingDownloadFiles.isNotEmpty()) {
                                startDownload(pendingDownloadFiles, uri)
                                pendingDownloadFiles = emptyList()
                            }
                        }
                    }

                    val pickUploadFilesLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenMultipleDocuments()
                    ) { uris ->
                        if (uris.isNotEmpty()) {
                            lifecycleScope.launch {
                                transferManager.findUploadConflicts(uris, currentPath)
                                    .onSuccess { conflicts ->
                                        if (conflicts.isEmpty()) {
                                            uploadSelectedFiles(uris, ConflictResolution.RENAME)
                                        } else {
                                            pendingUploadUris = uris
                                            uploadConflictNames = conflicts
                                            showUploadConflictDialog = true
                                        }
                                    }
                                    .onFailure { err ->
                                        Log.e("ItShareTransfer", "Gagal memeriksa nama file tujuan", err)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Gagal memeriksa file tujuan: ${err.localizedMessage}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                        }
                    }

                    val exportBackupLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument("application/json")
                    ) { uri ->
                        if (uri != null) {
                            lifecycleScope.launch {
                                runCatching {
                                    val history = savedSessions.map {
                                        SessionHistoryItem(
                                            host = it.host,
                                            port = it.port,
                                            share = it.share,
                                            readOnly = it.readOnly,
                                            lastConnectedEpoch = it.lastConnectedEpoch
                                        )
                                    }
                                    val jsonContent = BackupManager.exportBackupJson(
                                        history = history,
                                        settings = mapOf(
                                            "client" to "ItShare Android",
                                            "theme_mode" to themeMode,
                                            "language" to language,
                                            "explore_view_mode" to exploreViewMode,
                                            "folder_sort_mode" to folderSortMode
                                        )
                                    )
                                    val output = contentResolver.openOutputStream(uri)
                                        ?: throw IllegalStateException("Tidak dapat membuat berkas cadangan")
                                    output.use { os ->
                                        os.write(jsonContent.toByteArray(Charsets.UTF_8))
                                    }
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Cadangan riwayat berhasil diekspor!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }.onFailure { err ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Gagal ekspor cadangan: ${err.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }

                    val importBackupLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            lifecycleScope.launch {
                                runCatching {
                                    val jsonContent = contentResolver.openInputStream(uri)?.use {
                                        it.bufferedReader(Charsets.UTF_8).readText()
                                    } ?: throw IllegalStateException("Tidak dapat membaca berkas cadangan")

                                    val currentHistory = savedSessions.map {
                                        SessionHistoryItem(
                                            host = it.host,
                                            port = it.port,
                                            share = it.share,
                                            readOnly = it.readOnly,
                                            lastConnectedEpoch = it.lastConnectedEpoch
                                        )
                                    }
                                    val backup = BackupManager.importBackupJson(jsonContent, currentHistory).getOrThrow()
                                    val imported = backup.history
                                    val existingKeys = savedSessions.mapTo(mutableSetOf()) {
                                        "${it.host.lowercase()}:${it.port}:${it.share.lowercase()}"
                                    }
                                    val newSessions = imported.filter { item ->
                                        val key = "${item.host.lowercase()}:${item.port}:${item.share.lowercase()}"
                                        existingKeys.add(key)
                                    }
                                    newSessions.forEach { item ->
                                        sessionStore.savePermanentSession(
                                            SmbPayload(
                                                version = 1,
                                                host = item.host,
                                                port = item.port,
                                                share = item.share,
                                                user = "",
                                                pass = "",
                                                readOnly = item.readOnly,
                                                expEpoch = null,
                                                lastConnectedEpoch = item.lastConnectedEpoch
                                            ),
                                            lastConnectedEpoch = item.lastConnectedEpoch
                                        )
                                    }
                                    savedSessions = sessionStore.getAllSavedSessions()
                                    backup.settings["theme_mode"]?.takeIf { it in setOf("system", "light", "dark") }?.let { mode ->
                                        themeMode = mode
                                        uiPreferences.edit { putString("theme_mode", mode) }
                                    }
                                    backup.settings["language"]?.takeIf { it in setOf("en", "id") }?.let { selectedLanguage ->
                                        language = selectedLanguage
                                        uiPreferences.edit { putString("language", selectedLanguage) }
                                    }
                                    backup.settings["explore_view_mode"]?.takeIf { it in setOf("list", "grid") }?.let { mode ->
                                        exploreViewMode = mode
                                        uiPreferences.edit { putString("explore_view_mode", mode) }
                                    }
                                    backup.settings["folder_sort_mode"]?.takeIf { it in setOf("recent", "name_asc", "name_desc") }?.let { mode ->
                                        folderSortMode = mode
                                        uiPreferences.edit { putString("folder_sort_mode", mode) }
                                    }
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (language == "id") {
                                            "Berhasil memulihkan ${newSessions.size} koneksi. Masukkan kredensial saat menyambungkan."
                                        } else {
                                            "Restored ${newSessions.size} connections. Enter credentials when reconnecting."
                                        },
                                        Toast.LENGTH_LONG
                                    ).show()
                                }.onFailure { err ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Gagal impor cadangan: ${err.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }

                    when (currentDestination) {
                        is AppDestination.Onboarding -> {
                            OnboardingScreen(
                                savedSessions = savedSessions,
                                selectedTab = dashboardTab,
                                onSelectedTabChange = { dashboardTab = it },
                                themeMode = themeMode,
                                onThemeModeChange = { mode ->
                                    themeMode = mode
                                    uiPreferences.edit { putString("theme_mode", mode) }
                                },
                                exploreViewMode = exploreViewMode,
                                onExploreViewModeChange = { mode ->
                                    exploreViewMode = mode
                                    uiPreferences.edit { putString("explore_view_mode", mode) }
                                },
                                folderSortMode = folderSortMode,
                                onFolderSortModeChange = { mode ->
                                    folderSortMode = mode
                                    uiPreferences.edit { putString("folder_sort_mode", mode) }
                                },
                                language = language,
                                onLanguageChange = { selected ->
                                    language = selected
                                    uiPreferences.edit { putString("language", selected) }
                                },
                                downloadFolderLabel = downloadFolderUri?.let { uri ->
                                    DocumentFile.fromTreeUri(this@MainActivity, uri)?.name
                                } ?: if (language == "id") "Belum dipilih" else "Not selected",
                                onSelectDownloadFolder = {
                                    pendingDownloadFiles = emptyList()
                                    pickDownloadFolderLauncher.launch(downloadFolderUri)
                                },
                                transferProgress = transferProgress,
                                onScanShareClicked = {
                                    currentDestination = AppDestination.Scanner
                                },
                                onReconnectSession = { session ->
                                    if (session.user.isBlank()) {
                                        manualInputSeed = session
                                        showManualInputDialog = true
                                    } else {
                                        connectToSmb(session)
                                    }
                                },
                                onDeleteSession = { session ->
                                    sessionStore.removeSession(session)
                                    savedSessions = sessionStore.getAllSavedSessions()
                                    Toast.makeText(this@MainActivity, "Sesi dihapus dari riwayat", Toast.LENGTH_SHORT).show()
                                },
                                onExportBackupClicked = {
                                    exportBackupLauncher.launch("itshare_backup_${System.currentTimeMillis() / 1000}.json")
                                },
                                onImportBackupClicked = {
                                    importBackupLauncher.launch(arrayOf("application/json"))
                                },
                                onCancelTransfer = {
                                    transferManager.cancelTransfer()
                                    Toast.makeText(this@MainActivity, "Transfer dibatalkan", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        is AppDestination.Scanner -> {
                            key(scannerResetKey) { QrScannerScreen(
                                language = language,
                                onQrCodeDetected = { rawScanned ->
                                    val qrKind = when {
                                        rawScanned.trimStart().startsWith("{") -> "SMB_JSON_CANDIDATE"
                                        rawScanned.trimStart().startsWith("WIFI:", ignoreCase = true) -> "WIFI"
                                        else -> "UNKNOWN"
                                    }
                                    Log.i("ItShareScan", "Processing QR: kind=$qrKind, length=${rawScanned.length}")
                                    val smbResult = QrParser.parseSmbPayload(rawScanned)
                                    smbResult.onSuccess { payload ->
                                        Log.i("ItShareScan", "SMB QR parsed: host=${payload.host}, port=${payload.port}, share=${payload.share}")
                                        connectToSmb(payload)
                                    }.onFailure { smbParseError ->
                                        Log.d("ItShareScan", "SMB parser did not accept QR: ${smbParseError.javaClass.simpleName}: ${smbParseError.localizedMessage}")
                                        QrParser.parseWifiPayload(rawScanned).onSuccess { wifiPayload ->
                                            Log.i("ItShareScan", "Wi-Fi QR parsed; requesting connection (SSID omitted from log for privacy)")
                                            val wifiPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                                            } else {
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            }
                                            if (wifiPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                                                connectToWifi(wifiPayload)
                                            } else {
                                                pendingWifiPayload = wifiPayload
                                                wifiPermissionLauncher.launch(wifiPermissions)
                                            }
                                        }.onFailure { wifiError ->
                                            Log.e("ItShareScan", "QR is neither valid SMB nor Wi-Fi: ${wifiError.javaClass.simpleName}: ${wifiError.localizedMessage}", wifiError)
                                            val smbError = smbResult.exceptionOrNull()?.localizedMessage
                                            val reason = smbError?.takeIf { it.isNotBlank() }
                                                ?: wifiError.localizedMessage
                                                ?: "Format tidak didukung"
                                            Toast.makeText(
                                                this@MainActivity,
                                                "QR terbaca, tetapi formatnya tidak valid: $reason",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            scannerResetKey++
                                        }
                                    }
                                },
                                onManualInputClicked = {
                                    manualInputSeed = null
                                    showManualInputDialog = true
                                },
                                onBackClicked = {
                                    currentDestination = AppDestination.Onboarding
                                }
                            ) }
                        }

                        is AppDestination.Browser -> {
                            FileBrowserScreen(
                                language = language,
                                shareName = currentShareName,
                                connectionKey = smbManager.getActivePayload()?.let { "${it.host}:${it.port}/${it.share}" } ?: currentShareName,
                                currentPath = currentPath,
                                files = files,
                                isReadOnly = isReadOnly,
                                isLoading = isLoading,
                                exploreViewMode = exploreViewMode,
                                onExploreViewModeChange = { mode ->
                                    exploreViewMode = mode
                                    uiPreferences.edit { putString("explore_view_mode", mode) }
                                },
                                transferProgress = transferProgress,
                                onNavigateInto = { folder ->
                                    if (folder.isDirectory) loadDirectory(folder.path)
                                },
                                onNavigateToPath = { path ->
                                    loadDirectory(path)
                                },
                                onNavigateUp = {
                                    val parent = if (currentPath.contains("/")) {
                                        currentPath.substringBeforeLast("/")
                                    } else {
                                        ""
                                    }
                                    loadDirectory(parent)
                                },
                                onRefresh = {
                                    loadDirectory(currentPath)
                                },
                                onDownloadFiles = { selected ->
                                    val savedTarget = downloadFolderUri

                                    val targetDir = savedTarget?.let { DocumentFile.fromTreeUri(this@MainActivity, it) }
                                    if (savedTarget != null && targetDir != null && targetDir.canWrite()) {
                                        startDownload(selected, savedTarget)
                                    } else {

                                        if (savedTarget != null && targetDir?.canWrite() == false) {
                                            downloadFolderUri = null
                                            uiPreferences.edit { remove("download_folder_uri") }
                                        }
                                        pendingDownloadFiles = selected
                                        pickDownloadFolderLauncher.launch(null)
                                    }
                                },
                                onLoadThumbnail = { item, maxSize -> loadSmbThumbnail(smbManager, item, maxSize) },
                                onLoadImage = { item -> loadSmbFullImage(smbManager, item) },
                                onCreateMediaSource = { item ->
                                    smbManager.openMediaDataSource(item.path, item.sizeBytes).getOrThrow()
                                },
                                onUploadClicked = {
                                    pickUploadFilesLauncher.launch(arrayOf("*/*"))
                                },
                                onDeleteFile = { item ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            if (item.isDirectory) {
                                                smbManager.deleteDirectory(item.path)
                                            } else {
                                                smbManager.deleteFile(item.path)
                                            }
                                        }.onSuccess {
                                            Toast.makeText(this@MainActivity, "Berhasil menghapus ${item.name}", Toast.LENGTH_SHORT).show()
                                            loadDirectory(currentPath)
                                        }.onFailure { err ->
                                            Toast.makeText(this@MainActivity, "Gagal menghapus: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                onDeleteFiles = { selected ->
                                    val refreshPath = currentPath
                                    lifecycleScope.launch {
                                        val failures = mutableListOf<String>()
                                        selected
                                            .sortedByDescending { item -> item.path.count { it == '/' } }
                                            .forEach { item ->
                                                val result = if (item.isDirectory) {
                                                    smbManager.deleteDirectory(item.path)
                                                } else {
                                                    smbManager.deleteFile(item.path)
                                                }
                                                result.onFailure { failures += item.name }
                                            }
                                        loadDirectory(refreshPath)
                                        if (failures.isEmpty()) {
                                            Toast.makeText(this@MainActivity, "Berhasil menghapus item terpilih", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "Gagal menghapus: ${failures.joinToString()}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                onCreateFolder = { folderName ->
                                    lifecycleScope.launch {
                                        runCatching {
                                            val targetPath = if (currentPath.isEmpty()) folderName else "$currentPath/$folderName"
                                            smbManager.createDirectory(targetPath)
                                        }.onSuccess {
                                            Toast.makeText(this@MainActivity, "Folder '$folderName' berhasil dibuat", Toast.LENGTH_SHORT).show()
                                            loadDirectory(currentPath)
                                        }.onFailure { err ->
                                            Toast.makeText(this@MainActivity, "Gagal membuat folder: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                onCancelTransfer = {
                                    transferManager.cancelTransfer()
                                    Toast.makeText(this@MainActivity, "Transfer dibatalkan", Toast.LENGTH_SHORT).show()
                                },
                                onDisconnectClicked = {
                                    if (transferManager.isTransferRunning()) {
                                        Toast.makeText(this@MainActivity, "Batalkan transfer sebelum memutuskan koneksi.", Toast.LENGTH_LONG).show()
                                    } else {
                                        lifecycleScope.launch {
                                            directoryLoadJob?.cancel()
                                            directoryLoadJob = null
                                            smbManager.disconnect()
                                            files = emptyList()
                                            currentPath = ""
                                            currentShareName = ""
                                            currentDestination = AppDestination.Onboarding
                                        }
                                    }
                                },
                                onBackAtRoot = { currentDestination = AppDestination.Onboarding }
                            )
                        }
                    }

                    if (showUploadConflictDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                showUploadConflictDialog = false
                                pendingUploadUris = emptyList()
                            },
                            title = { Text("Nama berkas sudah ada") },
                            text = {
                                Column {
                                    Text("Pilih tindakan untuk nama yang bentrok pada unggahan ini:")
                                    Text(
                                        text = uploadConflictNames.take(5).joinToString("\n") { "• $it" } +
                                            if (uploadConflictNames.size > 5) "\n…dan ${uploadConflictNames.size - 5} lainnya" else "",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                Column {
                                    TextButton(
                                        onClick = { uploadSelectedFiles(pendingUploadUris, ConflictResolution.OVERWRITE) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Timpa berkas yang ada") }
                                    TextButton(
                                        onClick = { uploadSelectedFiles(pendingUploadUris, ConflictResolution.SKIP) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Lewati berkas duplikat") }
                                    TextButton(
                                        onClick = { uploadSelectedFiles(pendingUploadUris, ConflictResolution.RENAME) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Tambah dengan nama baru") }
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showUploadConflictDialog = false
                                        pendingUploadUris = emptyList()
                                    }
                                ) { Text("Batal") }
                            }
                        )
                    }

                    smbConnectError?.let { errorMessage ->
                        AlertDialog(
                            onDismissRequest = {
                                smbConnectError = null
                                scannerResetKey++
                            },
                            title = { Text(if (language == "id") "Login SMB gagal" else "SMB sign-in failed") },
                            text = {
                                Text(if (language == "id") "$errorMessage\n\nPeriksa kredensial dan pastikan berbagi aktif di PC." else "$errorMessage\n\nCheck the credentials and make sure sharing is active on the PC.")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    smbConnectError = null
                                    scannerResetKey++
                                }) { Text(if (language == "id") "Pindai ulang" else "Scan again") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    smbConnectError = null
                                    currentDestination = AppDestination.Onboarding
                                }) { Text(if (language == "id") "Kembali" else "Back") }
                            }
                        )
                    }

                    if (showManualInputDialog) {
                        ManualInputDialog(
                            language = language,
                            initialPayload = manualInputSeed,
                            onDismiss = { showManualInputDialog = false; manualInputSeed = null },
                            onConnect = { payload ->
                                showManualInputDialog = false
                                manualInputSeed = null
                                connectToSmb(payload)
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        transferManager.cancelTransfer()
        wifiConnector.disconnect()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            smbManager.disconnect()
        }
        super.onDestroy()
    }
}
