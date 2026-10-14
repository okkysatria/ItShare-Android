package com.itshare.client

import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.widget.Toast
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.lifecycleScope
import com.itshare.client.core.backup.BackupManager
import com.itshare.client.core.backup.SessionHistoryItem
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.model.SmbPayload
import com.itshare.client.core.model.ConflictResolution
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
                    var scannerResetKey by remember { mutableStateOf(0) }
                    var dashboardTab by remember { mutableStateOf(DashboardTab.FOLDERS) }
                    var currentPath by remember { mutableStateOf("") }
                    var currentShareName by remember { mutableStateOf("") }
                    var files by remember { mutableStateOf(emptyList<SmbFileItem>()) }
                    var isLoading by remember { mutableStateOf(false) }
                    var isReadOnly by remember { mutableStateOf(false) }
                    var showManualInputDialog by remember { mutableStateOf(false) }
                    var smbConnectError by remember { mutableStateOf<String?>(null) }
                    var savedSessions by remember { mutableStateOf(sessionStore.getAllSavedSessions()) }
                    var pendingDownloadFiles by remember { mutableStateOf<List<SmbFileItem>>(emptyList()) }
                    var pendingUploadUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
                    var uploadConflictNames by remember { mutableStateOf<List<String>>(emptyList()) }
                    var showUploadConflictDialog by remember { mutableStateOf(false) }

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
                        isLoading = true
                        val normalizedPath = path.replace('\\', '/').trim('/')
                        Log.d("ItShareBrowser", "Loading directory '${normalizedPath.ifBlank { "." }}'")
                        lifecycleScope.launch {
                            val result = smbManager.listFiles(normalizedPath)
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
                        Log.i("ItShareSMB", "Connecting: host=${payload.host}, port=${payload.port}, share=${payload.share}, readOnly=${payload.readOnly}")
                        isLoading = true
                        lifecycleScope.launch {
                            val result = smbManager.connect(payload)
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
                            uiPreferences.edit().putString("download_folder_uri", uri.toString()).apply()
                            if (pendingDownloadFiles.isNotEmpty()) {
                                startDownload(pendingDownloadFiles, uri)
                                pendingDownloadFiles = emptyList()
                            }
                        }
                    }

                    val pickUploadFilesLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenMultipleDocuments()
                    ) { uris ->
                        if (!uris.isNullOrEmpty()) {
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
                                            lastConnectedEpoch = System.currentTimeMillis() / 1000
                                        )
                                    }
                                    val jsonContent = BackupManager.exportBackupJson(
                                        history = history,
                                        settings = mapOf("client" to "ItShare Android")
                                    )
                                    contentResolver.openOutputStream(uri)?.use { os ->
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
                                            lastConnectedEpoch = 0L
                                        )
                                    }
                                    val imported = BackupManager.importBackupJson(jsonContent, currentHistory).getOrThrow()
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Berhasil mengimpor ${imported.size} riwayat sesi!",
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

                    when (val dest = currentDestination) {
                        is AppDestination.Onboarding -> {
                            OnboardingScreen(
                                savedSessions = savedSessions,
                                selectedTab = dashboardTab,
                                onSelectedTabChange = { dashboardTab = it },
                                themeMode = themeMode,
                                onThemeModeChange = { mode ->
                                    themeMode = mode
                                    uiPreferences.edit().putString("theme_mode", mode).apply()
                                },
                                exploreViewMode = exploreViewMode,
                                onExploreViewModeChange = { mode ->
                                    exploreViewMode = mode
                                    uiPreferences.edit().putString("explore_view_mode", mode).apply()
                                },
                                language = language,
                                onLanguageChange = { selected ->
                                    language = selected
                                    uiPreferences.edit().putString("language", selected).apply()
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
                                onManualInputClicked = {
                                    showManualInputDialog = true
                                },
                                onReconnectSession = { session ->
                                    connectToSmb(session)
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
                                            lifecycleScope.launch {
                                                Toast.makeText(this@MainActivity, "Menghubungkan ke Wi-Fi ${wifiPayload.ssid}...", Toast.LENGTH_SHORT).show()
                                                wifiConnector.connect(wifiPayload)
                                                    .onSuccess { Toast.makeText(this@MainActivity, "Wi-Fi tersambung. Pindai QR SMB untuk membuka folder.", Toast.LENGTH_LONG).show(); scannerResetKey++ }
                                                    .onFailure { err ->
                                                        Log.e("ItShareScan", "Wi-Fi connection failed", err)
                                                        Toast.makeText(this@MainActivity, "Gagal sambung Wi-Fi: ${err.localizedMessage}", Toast.LENGTH_LONG).show(); scannerResetKey++
                                                    }
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
                                currentPath = currentPath,
                                files = files,
                                isReadOnly = isReadOnly,
                                isLoading = isLoading,
                                exploreViewMode = exploreViewMode,
                                onExploreViewModeChange = { mode ->
                                    exploreViewMode = mode
                                    uiPreferences.edit().putString("explore_view_mode", mode).apply()
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
                                            uiPreferences.edit().remove("download_folder_uri").apply()
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
                                    lifecycleScope.launch {
                                        smbManager.disconnect()
                                        currentDestination = AppDestination.Onboarding
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
                            onDismiss = { showManualInputDialog = false },
                            onConnect = { payload ->
                                showManualInputDialog = false
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
        lifecycleScope.launch {
            smbManager.disconnect()
        }
        super.onDestroy()
    }
}
