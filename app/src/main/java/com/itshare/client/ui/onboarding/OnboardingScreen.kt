package com.itshare.client.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.itshare.client.core.model.SmbPayload
import com.itshare.client.core.transfer.TransferProgress
import com.itshare.client.ui.theme.itShareScreenGutter
import com.itshare.client.ui.uiText
import java.util.Locale
import java.util.Date
import java.util.Calendar
import java.text.SimpleDateFormat

enum class DashboardTab {
    FOLDERS,
    TRANSFERS,
    SETTINGS
}

@Composable
fun OnboardingScreen(
    savedSessions: List<SmbPayload>,
    selectedTab: DashboardTab,
    onSelectedTabChange: (DashboardTab) -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    downloadFolderLabel: String,
    onSelectDownloadFolder: () -> Unit,
    onScanShareClicked: () -> Unit,
    onReconnectSession: (SmbPayload) -> Unit,
    onDeleteSession: (SmbPayload) -> Unit,
    onExportBackupClicked: () -> Unit,
    onImportBackupClicked: () -> Unit,
    modifier: Modifier = Modifier,
    exploreViewMode: String = "list",
    onExploreViewModeChange: (String) -> Unit = {},
    folderSortMode: String = "recent",
    onFolderSortModeChange: (String) -> Unit = {},
    transferProgress: TransferProgress = TransferProgress(),
    onCancelTransfer: () -> Unit = {}
) {
    val tabHistory = remember { mutableStateListOf<DashboardTab>() }
    fun selectTab(tab: DashboardTab) {
        if (tab != selectedTab) {
            tabHistory.add(selectedTab)
            onSelectedTabChange(tab)
        }
    }
    BackHandler(enabled = selectedTab != DashboardTab.FOLDERS || tabHistory.isNotEmpty()) {
        when {
            tabHistory.isNotEmpty() -> onSelectedTabChange(tabHistory.removeAt(tabHistory.lastIndex))
            else -> onSelectedTabChange(DashboardTab.FOLDERS)
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                if (selectedTab == DashboardTab.FOLDERS) {
                    Button(
                        onClick = onScanShareClicked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = itShareScreenGutter(), vertical = 12.dp)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(uiText(language, "Add Folder", "Tambah Folder"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        HorizontalDivider(thickness = 0.7.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .height(64.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val tabs = listOf(
                                DashboardTab.FOLDERS to uiText(language, "My Folders", "Folder Saya"),
                                DashboardTab.TRANSFERS to uiText(language, "History", "Riwayat"),
                                DashboardTab.SETTINGS to uiText(language, "Settings", "Pengaturan")
                            )
                            tabs.forEach { (tab, label) ->
                                val isSelected = selectedTab == tab
                                val icon = when (tab) {
                                    DashboardTab.FOLDERS -> Icons.Default.Folder
                                    DashboardTab.TRANSFERS -> Icons.Default.History
                                    DashboardTab.SETTINGS -> Icons.Default.Settings
                                }
                                val itemColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { selectTab(tab) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Top
                                ) {
                                    Spacer(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    )
                                    Spacer(Modifier.weight(0.45f))
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = itemColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = itemColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(Modifier.weight(0.45f))
                                }
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
            ) {

            if (transferProgress.isTransferring && selectedTab != DashboardTab.TRANSFERS) {
                ItShareThroughputBanner(transferProgress = transferProgress)
            }

            when (selectedTab) {
                DashboardTab.FOLDERS -> {
                    FoldersTabContent(
                        savedSessions = savedSessions,
                        language = language,
                        folderSortMode = folderSortMode,
                        onFolderSortModeChange = onFolderSortModeChange,
                        onReconnectSession = onReconnectSession,
                        onDeleteSession = onDeleteSession
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
                        .background(if (transferProgress.isTransferring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
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
                        tint = MaterialTheme.colorScheme.tertiary
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
    onReconnectSession: (SmbPayload) -> Unit,
    onDeleteSession: (SmbPayload) -> Unit,
    modifier: Modifier = Modifier,
    language: String = "en",
    folderSortMode: String = "recent",
    onFolderSortModeChange: (String) -> Unit = {}
) {
    var showFolderSortMenu by remember { mutableStateOf(false) }
    val sortedSessions = remember(savedSessions, folderSortMode) {
        when (folderSortMode) {
            "name_asc" -> savedSessions.sortedWith(
                compareBy<SmbPayload> { it.share.lowercase(Locale.ROOT) }
                    .thenBy { it.host.lowercase(Locale.ROOT) }
            )
            "name_desc" -> savedSessions.sortedWith(
                compareByDescending<SmbPayload> { it.share.lowercase(Locale.ROOT) }
                    .thenBy { it.host.lowercase(Locale.ROOT) }
            )
            else -> savedSessions.sortedWith(
                compareByDescending<SmbPayload> { it.lastConnectedEpoch }
                    .thenBy { it.share.lowercase(Locale.ROOT) }
            )
        }
    }
    val folderSortLabel = when (folderSortMode) {
        "name_asc" -> uiText(language, "Name A–Z", "Nama A–Z")
        "name_desc" -> uiText(language, "Name Z–A", "Nama Z–A")
        else -> uiText(language, "Last opened", "Terakhir dibuka")
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = itShareScreenGutter()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 12.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("ItShare", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(uiText(language, "Saved sessions", "Sesi tersimpan"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Box {
                    IconButton(onClick = { showFolderSortMenu = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "${uiText(language, "Sort folders", "Urutkan folder")}: $folderSortLabel",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = showFolderSortMenu,
                        onDismissRequest = { showFolderSortMenu = false }
                    ) {
                        listOf(
                            "name_asc" to uiText(language, "Name A–Z", "Nama A–Z"),
                            "name_desc" to uiText(language, "Name Z–A", "Nama Z–A"),
                            "recent" to uiText(language, "Last opened", "Terakhir dibuka")
                        ).forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    if (folderSortMode == mode) Icon(Icons.Default.Check, contentDescription = null)
                                },
                                onClick = {
                                    onFolderSortModeChange(mode)
                                    showFolderSortMenu = false
                                }
                            )
                        }
                    }
                }
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
                    }
                }
            }
        } else {
            items(sortedSessions, key = { "${it.host}:${it.port}:${it.share}" }) { session ->
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
    modifier: Modifier = Modifier,
    language: String = "en"
) {
    var showOptions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = session.share,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (session.readOnly) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = uiText(language, "Read only", "Baca saja"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (session.user.isBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = uiText(language, "Login needed", "Perlu login"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${session.host}:${session.port} · ${session.user.ifBlank { uiText(language, "Credentials required", "Kredensial diperlukan") }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
    onCancelTransfer: () -> Unit,
    modifier: Modifier = Modifier,
    language: String = "en"
) {
    var transferHistoryPage by remember { mutableIntStateOf(0) }
    val historyPageSize = 5
    val historyPageCount = (transferProgress.recentTransfers.size + historyPageSize - 1) / historyPageSize
    LaunchedEffect(historyPageCount) {
        transferHistoryPage = if (historyPageCount == 0) 0
        else transferHistoryPage.coerceIn(0, historyPageCount - 1)
    }
    val speedLabel = formatSpeed(transferProgress.speedBytesPerSec).removeSuffix("/s") + uiText(language, "/s", "/dtk")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = itShareScreenGutter(), end = itShareScreenGutter(), top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = uiText(language, "History", "Riwayat"),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = uiText(language, "One card for each transfer", "Satu kartu untuk setiap proses transfer"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (transferProgress.isTransferring) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (transferProgress.fileIndex == 0) {
                                if (transferProgress.isDownload) uiText(language, "Preparing download", "Menyiapkan unduhan")
                                else uiText(language, "Preparing upload", "Menyiapkan unggahan")
                            } else {
                                "${if (transferProgress.isDownload) uiText(language, "Downloading", "Mengunduh") else uiText(language, "Uploading", "Mengunggah")} ${transferProgress.fileIndex} ${uiText(language, "of", "dari")} ${transferProgress.totalFiles}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = if (transferProgress.totalBytes > 0L) {
                            "${transferProgress.currentFileName} · ${formatFileSize(transferProgress.bytesTransferred)} ${uiText(language, "of", "dari")} ${formatFileSize(transferProgress.totalBytes)} · $speedLabel"
                        } else {
                            "${transferProgress.currentFileName} · ${formatFileSize(transferProgress.bytesTransferred)} · $speedLabel"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (transferProgress.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { transferProgress.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            trackColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            trackColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (transferProgress.totalBytes > 0L) {
                                "${transferProgress.progressPercent}%"
                            } else {
                                formatFileSize(transferProgress.bytesTransferred)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        OutlinedButton(
                            onClick = onCancelTransfer,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                        ) {
                            Text(uiText(language, "Cancel", "Batal"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (transferProgress.recentTransfers.isNotEmpty()) {
            val visibleGroups = transferProgress.recentTransfers
                .drop(transferHistoryPage * historyPageSize)
                .take(historyPageSize)
            visibleGroups.forEachIndexed { index, group ->
                var expanded by remember(group.id) { mutableStateOf(index == 0 && transferHistoryPage == 0) }
                var filePage by remember(group.id) { mutableIntStateOf(0) }
                val filesPerGroupPage = 30
                val filePageCount = (group.items.size + filesPerGroupPage - 1) / filesPerGroupPage
                LaunchedEffect(filePageCount) {
                    filePage = if (filePageCount == 0) 0
                    else filePage.coerceIn(0, filePageCount - 1)
                }
                val succeeded = group.items.all { it.isSuccess }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surface) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (group.isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${if (group.isDownload) uiText(language, "Download", "Unduh") else uiText(language, "Upload", "Unggah")} • ${group.items.size} ${uiText(language, "files", "berkas")}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${formatFileSize(group.items.sumOf { it.sizeBytes })} · ${formatTransferDate(group.timestampEpoch, language)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sessionLabel = listOf(group.serverAddress, group.shareName)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                    val folderLabel = if (group.isDownload) {
                                        group.localFolderName.takeIf { it.isNotBlank() }
                                    } else {
                                        group.remoteFolderPath.ifBlank { "/" }
                                    }
                                    val contextLabel = listOfNotNull(
                                        sessionLabel.takeIf { it.isNotBlank() },
                                        folderLabel?.let { folder ->
                                            "→ $folder"
                                        }
                                    ).joinToString("  ·  ")
                                    if (contextLabel.isNotBlank()) {
                                        Text(
                                            text = contextLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (succeeded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = if (succeeded) uiText(language, "Complete", "Selesai") else uiText(language, "Failed", "Gagal"),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (succeeded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        if (expanded) {
                            group.items.drop(filePage * filesPerGroupPage).take(filesPerGroupPage).forEach { file ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 66.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                        if (file.locationPath.isNotBlank()) {
                                            Text(
                                                text = file.locationPath,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(formatFileSize(file.sizeBytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (filePageCount > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 66.dp, end = 12.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { filePage-- }, enabled = filePage > 0) { Text(uiText(language, "Previous", "Sebelumnya")) }
                                    Text("${filePage + 1} / $filePageCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { filePage++ }, enabled = filePage < filePageCount - 1) { Text(uiText(language, "Next", "Berikutnya")) }
                                }
                            }
                        }
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
        } else if (!transferProgress.isTransferring) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    uiText(language, "No transfer history yet", "Belum ada riwayat transfer"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
    }
}

private fun formatTransferDate(timestampEpoch: Long, language: String): String {
    val transferDate = Date(timestampEpoch * 1000)
    val now = Calendar.getInstance()
    val transferCalendar = Calendar.getInstance().apply { time = transferDate }
    val time = SimpleDateFormat("HH.mm", Locale.getDefault()).format(transferDate)
    val isSameDay = now.get(Calendar.YEAR) == transferCalendar.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == transferCalendar.get(Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == transferCalendar.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == transferCalendar.get(Calendar.DAY_OF_YEAR)
    return when {
        isSameDay -> "${uiText(language, "Today", "Hari ini")} $time"
        isYesterday -> "${uiText(language, "Yesterday", "Kemarin")} $time"
        else -> "${SimpleDateFormat("dd MMM", Locale.getDefault()).format(transferDate)} $time"
    }
}

@Composable
fun SettingsTabContent(
    onExportBackupClicked: () -> Unit,
    onImportBackupClicked: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    downloadFolderLabel: String,
    onSelectDownloadFolder: () -> Unit,
    modifier: Modifier = Modifier,
    exploreViewMode: String = "list",
    onExploreViewModeChange: (String) -> Unit = {}
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
    val containerWidth = LocalWindowInfo.current.containerSize.width
    val screenWidthDp = with(LocalDensity.current) { containerWidth.toDp().value }
    val compactWidth = screenWidthDp < 380

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = itShareScreenGutter()),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {
            Text(
                text = uiText(language, "Settings", "Pengaturan"),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = networkStatusLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (totalBytes > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val storageSummary = if (language == "id") {
                            "Sisa ${formatStorageDisplay(freeBytes)} dari ${formatStorageDisplay(totalBytes)}"
                        } else {
                            "${formatStorageDisplay(freeBytes)} free of ${formatStorageDisplay(totalBytes)}"
                        }
                        if (compactWidth) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = uiText(language, "Phone storage", "Penyimpanan HP"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(storageSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else Row(
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
                                text = storageSummary,
                                style = MaterialTheme.typography.bodyMedium,
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
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
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
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                SettingsSectionCard {
                    Column {

                        SettingsRow(
                            icon = Icons.Default.Contrast,
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
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 68.dp)
                        )

                        SettingsRow(
                            icon = Icons.Default.GridView,
                            title = uiText(language, "File view", "Tampilan berkas"),
                            value = if (exploreViewMode == "grid") uiText(language, "Grid", "Kisi") else uiText(language, "List", "Daftar"),
                            onClick = { showExploreViewSheet = true }
                        )

                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 68.dp)
                        )

                        SettingsRow(
                            iconLabel = "Aa",
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
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                SettingsSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDownloadFolder() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

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
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                SettingsSectionCard {
                    Column {

                        SettingsRow(
                            icon = Icons.Default.ArrowUpward,
                            title = uiText(language, "Export PC list", "Ekspor daftar PC"),
                            subtitle = uiText(language, "Save saved sessions to JSON file", "Simpan sesi tersimpan ke berkas JSON"),
                            onClick = onExportBackupClicked
                        )

                        HorizontalDivider(
                            thickness = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 68.dp)
                        )

                        SettingsRow(
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
private fun SettingsSectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector? = null,
    iconLabel: String? = null,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    icon != null -> Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    iconLabel != null -> Text(
                        text = iconLabel,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 15.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (subtitle.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .background(MaterialTheme.colorScheme.outlineVariant)
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
                modifier = Modifier.padding(bottom = 8.dp)
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
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
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
