package com.itshare.client.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.ui.platform.LocalContext
import com.itshare.client.core.model.SmbPayload
import com.itshare.client.core.transfer.TransferProgress
import com.itshare.client.ui.theme.ItShareGreen
import com.itshare.client.ui.theme.ItShareAccent
import com.itshare.client.ui.uiText
import java.util.Locale

enum class DashboardTab {
    FOLDERS,
    TRANSFERS,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    savedSessions: List<SmbPayload>,
    selectedTab: DashboardTab,
    onSelectedTabChange: (DashboardTab) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    exploreViewMode: String = "list",
    onExploreViewModeChange: (String) -> Unit = {},
    language: String,
    onLanguageChange: (String) -> Unit,
    downloadFolderLabel: String,
    onSelectDownloadFolder: () -> Unit,
    transferProgress: TransferProgress = TransferProgress(),
    onScanShareClicked: () -> Unit,
    onManualInputClicked: () -> Unit,
    onReconnectSession: (SmbPayload) -> Unit,
    onDeleteSession: (SmbPayload) -> Unit,
    onExportBackupClicked: () -> Unit,
    onImportBackupClicked: () -> Unit,
    onCancelTransfer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tabHistory = remember { mutableStateListOf<DashboardTab>() }
    var showAddShareSheet by remember { mutableStateOf(false) }
    fun selectTab(tab: DashboardTab) {
        if (tab != selectedTab) {
            tabHistory.add(selectedTab)
            onSelectedTabChange(tab)
        }
    }
    BackHandler(enabled = showAddShareSheet || selectedTab != DashboardTab.FOLDERS || tabHistory.isNotEmpty()) {
        when {
            showAddShareSheet -> showAddShareSheet = false
            tabHistory.isNotEmpty() -> onSelectedTabChange(tabHistory.removeAt(tabHistory.lastIndex))
            else -> onSelectedTabChange(DashboardTab.FOLDERS)
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                if (selectedTab == DashboardTab.FOLDERS && savedSessions.isNotEmpty()) {
                    Button(
                        onClick = { showAddShareSheet = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 13.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(uiText(language, "Add folder", "Tambah folder"), fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE6EDE8))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .height(68.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tabs = listOf(
                                Triple(DashboardTab.FOLDERS, Icons.Default.Folder, uiText(language, "My Folders", "Folder Saya")),
                                Triple(DashboardTab.TRANSFERS, Icons.Default.History, uiText(language, "History", "Riwayat")),
                                Triple(DashboardTab.SETTINGS, Icons.Default.Settings, uiText(language, "Settings", "Pengaturan"))
                            )
                            tabs.forEach { (tab, icon, label) ->
                                val isSelected = selectedTab == tab
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { selectTab(tab) }
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (isSelected) Color(0xFFDCEDE6) else Color.Transparent)
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (tab == DashboardTab.TRANSFERS && transferProgress.isTransferring) {
                                            BadgedBox(
                                                badge = {
                                                    Badge(
                                                        containerColor = Color(0xFF0F684B),
                                                        contentColor = Color.White
                                                    ) { Text("1") }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = label,
                                                    tint = if (isSelected) Color(0xFF0F5136) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = label,
                                                tint = if (isSelected) Color(0xFF0F5136) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color(0xFF0F684B) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            if (transferProgress.isTransferring) {
                ItShareThroughputBanner(transferProgress = transferProgress)
            }

            when (selectedTab) {
                DashboardTab.FOLDERS -> {
                    FoldersTabContent(
                        savedSessions = savedSessions,
                        language = language,
                        onReconnectSession = onReconnectSession,
                        onDeleteSession = onDeleteSession,
                        onAddFolderClicked = { showAddShareSheet = true }
                    )
                }

                DashboardTab.TRANSFERS -> {
                    TransfersTabContent(
                        transferProgress = transferProgress,
                        language = language,
                        onCancelTransfer = onCancelTransfer
                    )
                }

                DashboardTab.SETTINGS -> {
                    SettingsTabContent(
                        onExportBackupClicked = onExportBackupClicked,
                        onImportBackupClicked = onImportBackupClicked,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        exploreViewMode = exploreViewMode,
                        onExploreViewModeChange = onExploreViewModeChange,
                        language = language,
                        onLanguageChange = onLanguageChange,
                        downloadFolderLabel = downloadFolderLabel,
                        onSelectDownloadFolder = onSelectDownloadFolder
                    )
                }
            }
        }

        if (showAddShareSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddShareSheet = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                AddShareBottomSheetContent(
                    language = language,
                    onScanShare = {
                        showAddShareSheet = false
                        onScanShareClicked()
                    },
                    onManualInput = {
                        showAddShareSheet = false
                        onManualInputClicked()
                    }
                )
            }
        }
    }
}

@Composable
fun ItShareThroughputBanner(
    transferProgress: TransferProgress,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (transferProgress.isTransferring) ItShareGreen else ItShareAccent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (transferProgress.isTransferring) "Transfer Berlangsung" else "Siap di jaringan lokal",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (transferProgress.isTransferring && transferProgress.isDownload)
                            formatSpeed(transferProgress.speedBytesPerSec)
                        else "0 B/s",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = ItShareAccent
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (transferProgress.isTransferring && !transferProgress.isDownload)
                            formatSpeed(transferProgress.speedBytesPerSec)
                        else "0 B/s",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun FoldersTabContent(
    savedSessions: List<SmbPayload>,
    language: String = "en",
    onReconnectSession: (SmbPayload) -> Unit,
    onDeleteSession: (SmbPayload) -> Unit,
    onAddFolderClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val networkLabel = remember(context, language) {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> uiText(language, "Wi-Fi connected", "Wi-Fi tersambung")
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> uiText(language, "Mobile data", "Data seluler")
            else -> uiText(language, "Offline", "Luring")
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 96.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(uiText(language, "LOCAL NETWORK", "JARINGAN LOKAL"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(uiText(language, "Shared folders", "Folder bersama"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (networkLabel == uiText(language, "Offline", "Luring")) MaterialTheme.colorScheme.error else ItShareAccent))
                        Spacer(Modifier.width(6.dp))
                        Text(networkLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(uiText(language, "Saved connections", "Koneksi tersimpan"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${savedSessions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (savedSessions.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(68.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.FolderShared, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(uiText(language, "No saved connections", "Belum ada koneksi"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(uiText(language, "Scan a QR code to connect.", "Pindai QR untuk terhubung."), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onAddFolderClicked, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(uiText(language, "Add connection", "Tambah koneksi"))
                        }
                    }
                }
            }
        } else {
            items(savedSessions, key = { "${it.host}:${it.port}:${it.share}" }) { session ->
                ItShareFolderCard(session = session, onOpen = { onReconnectSession(session) }, onDelete = { onDeleteSession(session) }, language = language)
            }
        }
    }
}

@Composable
fun ItShareFolderCard(
    session: SmbPayload,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    language: String = "en",
    modifier: Modifier = Modifier
) {
    var showOptions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(50.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = session.share,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = if (session.readOnly) uiText(language, "READ ONLY", "HANYA BACA") else uiText(language, "READ / WRITE", "BACA / TULIS"),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                letterSpacing = 0.5.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = "${session.host}:${session.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = session.user,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    IconButton(onClick = { showOptions = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = uiText(language, "Connection options", "Opsi koneksi"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    }
                    DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                        DropdownMenuItem(
                            text = { Text(uiText(language, "Remove saved connection", "Hapus koneksi tersimpan"), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showOptions = false; showDeleteConfirm = true }
                        )
                    }
                }
            }

        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(uiText(language, "Remove connection?", "Hapus koneksi?")) },
            text = { Text("${session.share} · ${session.host}") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text(uiText(language, "Remove", "Hapus"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(uiText(language, "Cancel", "Batal")) } }
        )
    }
}

@Composable
fun TransfersTabContent(
    transferProgress: TransferProgress,
    language: String = "en",
    onCancelTransfer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var transferHistoryPage by remember { mutableStateOf(0) }
    val historyPageSize = 5
    val historyPageCount = (transferProgress.recentTransfers.size + historyPageSize - 1) / historyPageSize
    LaunchedEffect(historyPageCount) {
        if (historyPageCount == 0) transferHistoryPage = 0
        else transferHistoryPage = transferHistoryPage.coerceIn(0, historyPageCount - 1)
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 96.dp)
    ) {
        Text(
            text = uiText(language, "TRANSFERS", "TRANSFER"),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (transferProgress.isTransferring) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (transferProgress.isDownload) Icons.Default.Download else Icons.Default.Upload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (transferProgress.isDownload) "Mengunduh" else "Mengunggah",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        TextButton(onClick = onCancelTransfer) {
                            Text(uiText(language, "Cancel", "Batal"), color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = transferProgress.currentFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { transferProgress.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${transferProgress.progressPercent}% • ${formatFileSize(transferProgress.bytesTransferred)} of ${formatFileSize(transferProgress.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatSpeed(transferProgress.speedBytesPerSec),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = ItShareAccent,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiText(language, "No active transfers", "Tidak ada transfer aktif"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = uiText(language, "Your transfers will appear here.", "Transfer akan tampil di sini."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (transferProgress.recentTransfers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = uiText(language, "RECENT TRANSFERS", "TRANSFER TERBARU"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                ) {
                    items(
                        transferProgress.recentTransfers.drop(transferHistoryPage * historyPageSize).take(historyPageSize),
                        key = { it.id }
                    ) { group ->
                        var expanded by remember(group.id) { mutableStateOf(false) }
                        var filePage by remember(group.id) { mutableStateOf(0) }
                        val filesPerGroupPage = 30
                        val filePageCount = (group.items.size + filesPerGroupPage - 1) / filesPerGroupPage
                        LaunchedEffect(filePageCount) {
                            if (filePageCount == 0) filePage = 0
                            else filePage = filePage.coerceIn(0, filePageCount - 1)
                        }
                        Column {
                            Surface(
                                onClick = { expanded = !expanded },
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (group.isDownload) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (group.isDownload) Icons.Default.Download else Icons.Default.Upload,
                                                contentDescription = null,
                                                tint = if (group.isDownload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${if (group.isDownload) uiText(language, "Download", "Unduh") else uiText(language, "Upload", "Unggah")} • ${group.items.size} ${uiText(language, "files", "berkas")}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "${group.items.sumOf { it.sizeBytes }.let(::formatFileSize)} • ${java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(group.timestampEpoch * 1000))}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (expanded) "Tutup daftar berkas" else "Lihat berkas",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (expanded) {
                                group.items.drop(filePage * filesPerGroupPage).take(filesPerGroupPage).forEach { file ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(8.dp))
                                        Text(file.fileName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                        Text(formatFileSize(file.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (filePageCount > 1) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { filePage-- }, enabled = filePage > 0) {
                                            Text(uiText(language, "Previous", "Sebelumnya"))
                                        }
                                        Text("${filePage + 1} / $filePageCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = { filePage++ }, enabled = filePage < filePageCount - 1) {
                                            Text(uiText(language, "Next", "Berikutnya"))
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 56.dp)
                        )
                    }
                }
            }
            if (historyPageCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { transferHistoryPage-- }, enabled = transferHistoryPage > 0) {
                        Text(uiText(language, "Previous", "Sebelumnya"))
                    }
                    Text(
                        text = "${transferHistoryPage + 1} / $historyPageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { transferHistoryPage++ }, enabled = transferHistoryPage < historyPageCount - 1) {
                        Text(uiText(language, "Next", "Berikutnya"))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTabContent(
    onExportBackupClicked: () -> Unit,
    onImportBackupClicked: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    exploreViewMode: String = "list",
    onExploreViewModeChange: (String) -> Unit = {},
    language: String,
    onLanguageChange: (String) -> Unit,
    downloadFolderLabel: String,
    onSelectDownloadFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var showThemeSheet by remember { mutableStateOf(false) }
    var showExploreViewSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    val networkStatusLabel = remember(context, language) {
        runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNetwork)
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                    uiText(language, "Connected via Wi-Fi", "Tersambung lewat Wi-Fi")
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                    uiText(language, "Connected via Mobile Data", "Tersambung lewat Data Seluler")
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                    uiText(language, "Connected via Ethernet", "Tersambung lewat Ethernet")
                else ->
                    uiText(language, "Disconnected", "Tidak tersambung")
            }
        }.getOrDefault(uiText(language, "Connected via Wi-Fi", "Tersambung lewat Wi-Fi"))
    }

    val (freeBytes, totalBytes) = remember {
        runCatching {
            val statFs = StatFs(Environment.getDataDirectory().path)
            val available = statFs.availableBlocksLong * statFs.blockSizeLong
            val total = statFs.blockCountLong * statFs.blockSizeLong
            Pair(available, total)
        }.getOrDefault(Pair(0L, 0L))
    }

    val usedPercent = remember(freeBytes, totalBytes) {
        if (totalBytes > 0) {
            val used = totalBytes - freeBytes
            (used.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        item {
            Text(
                text = uiText(language, "Settings", "Pengaturan"),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, Color(0xFFE6EDE8)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFDCEDE6),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = Color(0xFF14563A),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0F8A6B))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = networkStatusLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (totalBytes > 0) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiText(language, "Phone storage", "Penyimpanan HP"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (language == "id") {
                                    "Sisa ${formatStorageDisplay(freeBytes)} dari ${formatStorageDisplay(totalBytes)}"
                                } else {
                                    "${formatStorageDisplay(freeBytes)} free of ${formatStorageDisplay(totalBytes)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { usedPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF0F684B),
                            trackColor = Color(0xFFE2EBE5)
                        )
                    }
                }
            }
        }

        item {
            Column {
                Text(
                    text = uiText(language, "DISPLAY", "TAMPILAN"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF176B49),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Color(0xFFE6EDE8)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {

                        SettingsOptionRow(
                            icon = {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFDCEDE6),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Contrast,
                                            contentDescription = null,
                                            tint = Color(0xFF14563A),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                            title = uiText(language, "Theme", "Tema"),
                            value = when (themeMode) {
                                "light" -> uiText(language, "Light", "Terang")
                                "dark" -> uiText(language, "Dark", "Gelap")
                                else -> uiText(language, "Follow system", "Ikuti sistem")
                            },
                            onClick = { showThemeSheet = true }
                        )

                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = Color(0xFFF0F4F1),
                            modifier = Modifier.padding(start = 72.dp)
                        )

                        SettingsOptionRow(
                            icon = {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFDCEDE6),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.GridView,
                                            contentDescription = null,
                                            tint = Color(0xFF14563A),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                            title = uiText(language, "File view", "Tampilan berkas"),
                            value = if (exploreViewMode == "grid") uiText(language, "Grid", "Kisi") else uiText(language, "List", "Daftar"),
                            onClick = { showExploreViewSheet = true }
                        )

                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = Color(0xFFF0F4F1),
                            modifier = Modifier.padding(start = 72.dp)
                        )

                        SettingsOptionRow(
                            icon = {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFDCEDE6),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "Aa",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF14563A),
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            },
                            title = uiText(language, "Language", "Bahasa"),
                            value = if (language == "id") "Indonesia" else "English",
                            onClick = { showLanguageSheet = true }
                        )
                    }
                }
            }
        }

        item {
            Column {
                Text(
                    text = uiText(language, "DOWNLOADS", "UNDUHAN"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF176B49),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Color(0xFFE6EDE8)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDownloadFolder() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFDCEDE6),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = Color(0xFF14563A),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiText(language, "Save downloads to", "Simpan unduhan di"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = downloadFolderLabel.ifBlank { uiText(language, "Not selected", "Belum dipilih") },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = uiText(language, "Change", "Ubah"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF176B49)
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Color(0xFF8B9B91),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        item {
            Column {
                Text(
                    text = uiText(language, "BACKUP", "CADANGAN"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF176B49),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, Color(0xFFE6EDE8)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {

                        SettingsActionRow(
                            icon = Icons.Default.ArrowUpward,
                            title = uiText(language, "Export PC list", "Ekspor daftar PC"),
                            subtitle = uiText(language, "Save saved sessions to JSON file", "Simpan sesi tersimpan ke berkas JSON"),
                            onClick = onExportBackupClicked
                        )

                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = Color(0xFFF0F4F1),
                            modifier = Modifier.padding(start = 72.dp)
                        )

                        SettingsActionRow(
                            icon = Icons.Default.ArrowDownward,
                            title = uiText(language, "Import PC list", "Impor daftar PC"),
                            subtitle = uiText(language, "Restore sessions from JSON file", "Pulihkan sesi dari berkas JSON"),
                            onClick = onImportBackupClicked
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "ItShare versi 1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 28.dp)
            )
        }
    }

    if (showThemeSheet) {
        SettingsSelectionSheet(
            title = uiText(language, "Choose theme", "Pilih tema"),
            options = listOf(
                "system" to uiText(language, "Follow system", "Ikuti sistem"),
                "light" to uiText(language, "Light", "Terang"),
                "dark" to uiText(language, "Dark", "Gelap")
            ),
            selectedKey = themeMode,
            onSelect = onThemeModeChange,
            onDismiss = { showThemeSheet = false }
        )
    }

    if (showExploreViewSheet) {
        SettingsSelectionSheet(
            title = uiText(language, "Choose file view", "Pilih tampilan berkas"),
            options = listOf(
                "list" to uiText(language, "List", "Daftar"),
                "grid" to uiText(language, "Grid", "Kisi")
            ),
            selectedKey = exploreViewMode,
            onSelect = onExploreViewModeChange,
            onDismiss = { showExploreViewSheet = false }
        )
    }

    if (showLanguageSheet) {
        SettingsSelectionSheet(
            title = uiText(language, "Choose language", "Pilih bahasa"),
            options = listOf(
                "id" to "Indonesia",
                "en" to "English"
            ),
            selectedKey = language,
            onSelect = onLanguageChange,
            onDismiss = { showLanguageSheet = false }
        )
    }
}

@Composable
private fun SettingsOptionRow(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF176B49)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF8B9B91),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFDCEDE6),
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color(0xFF14563A),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF8B9B91),
            modifier = Modifier.size(18.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSelectionSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFCAD5CD))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            options.forEach { (key, label) ->
                val isSelected = selectedKey == key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(key)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF0F8A6B),
                            unselectedColor = Color(0xFF8B9B91)
                        )
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun formatStorageDisplay(bytes: Long): String {
    return String.format(Locale.GERMANY, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

@Composable
fun AddShareBottomSheetContent(
    language: String = "en",
    onScanShare: () -> Unit,
    onManualInput: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = uiText(language, "Add connection", "Tambah koneksi"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = uiText(language, "Connect to a PC.", "Sambungkan ke PC."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        AddShareOptionItem(
            icon = Icons.Default.QrCodeScanner,
            title = uiText(language, "Scan QR", "Pindai QR"),
            subtitle = uiText(language, "Scan an ItShare or Wi-Fi QR code", "Pindai QR ItShare atau Wi-Fi"),
            onClick = onScanShare
        )
        AddShareOptionItem(
            icon = Icons.Default.Edit,
            title = uiText(language, "Connect manually", "Sambungkan manual"),
            subtitle = uiText(language, "Enter the PC address and credentials", "Masukkan alamat PC dan kredensial"),
            onClick = onManualInput
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun AddShareOptionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024))
        bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}
