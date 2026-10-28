package com.itshare.client.ui.browser

import android.graphics.Bitmap
import android.media.MediaDataSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.transfer.TransferProgress
import com.itshare.client.ui.theme.itShareScreenGutter
import com.itshare.client.ui.uiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileBrowserScreen(
    shareName: String,
    currentPath: String,
    files: List<SmbFileItem>,
    isReadOnly: Boolean,
    isLoading: Boolean,
    onNavigateInto: (SmbFileItem) -> Unit,
    onNavigateToPath: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onDownloadFiles: (List<SmbFileItem>) -> Unit,
    onUploadClicked: () -> Unit,
    onCancelTransfer: () -> Unit,
    onDisconnectClicked: () -> Unit,
    onBackAtRoot: () -> Unit,
    modifier: Modifier = Modifier,
    language: String = "en",
    connectionKey: String = shareName,
    exploreViewMode: String = "list",
    onExploreViewModeChange: (String) -> Unit = {},
    transferProgress: TransferProgress = TransferProgress(),
    onLoadThumbnail: suspend (SmbFileItem, Int) -> Bitmap? = { _, _ -> null },
    onLoadImage: suspend (SmbFileItem) -> Bitmap? = { null },
    onCreateMediaSource: suspend (SmbFileItem) -> MediaDataSource = { error("Media preview is unavailable") },
    onDeleteFile: (SmbFileItem) -> Unit = {},
    onDeleteFiles: (List<SmbFileItem>) -> Unit = { items -> items.forEach(onDeleteFile) },
    onCreateFolder: (String) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var isGridView by remember(exploreViewMode) { mutableStateOf(exploreViewMode == "grid") }
    var sortBy by remember { mutableStateOf("name") }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<SmbFileItem>()) }
    var fileDetailItem by remember { mutableStateOf<SmbFileItem?>(null) }
    var mediaPreviewItem by remember { mutableStateOf<SmbFileItem?>(null) }
    var fileToDelete by remember { mutableStateOf<SmbFileItem?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showDisconnectConfirm -> showDisconnectConfirm = false
            mediaPreviewItem != null -> mediaPreviewItem = null
            fileToDelete != null -> fileToDelete = null
            fileDetailItem != null -> fileDetailItem = null
            showCreateFolderDialog -> showCreateFolderDialog = false
            selectedFiles.isNotEmpty() -> selectedFiles = emptySet()
            searchQuery.isNotEmpty() -> searchQuery = ""
            currentPath.isNotBlank() -> onNavigateUp()
            else -> onBackAtRoot()
        }
    }

    LaunchedEffect(currentPath) {
        selectedFiles = emptySet()
        searchQuery = ""
    }

    fun matchesCategory(item: SmbFileItem): Boolean {
        return when (selectedCategory) {
            "images" -> !item.isDirectory && item.isImageFile()
            "video" -> !item.isDirectory && item.isVideoFile()
            "documents" -> !item.isDirectory && item.name.substringAfterLast('.', "").lowercase() in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "zip", "7z", "rar")
            else -> true
        }
    }

    fun handleItemClick(item: SmbFileItem) {
        if (selectedFiles.isNotEmpty()) {
            selectedFiles = if (item in selectedFiles) selectedFiles - item else selectedFiles + item
        } else if (item.isDirectory) {
            onNavigateInto(item)
        } else if (item.isImageFile() || item.isVideoFile()) {
            mediaPreviewItem = item
        } else {
            selectedFiles = setOf(item)
        }
    }

    val filteredFiles = remember(files, searchQuery, selectedCategory, sortBy) {
        files.filter { item ->
            (searchQuery.isBlank() || item.name.contains(searchQuery.trim(), ignoreCase = true)) && matchesCategory(item)
        }.sortedWith(
            compareByDescending<SmbFileItem> { it.isDirectory }.thenComparator { first, second ->
                when (sortBy) {
                    "size" -> first.sizeBytes.compareTo(second.sizeBytes)
                    "date" -> second.lastModifiedEpoch.compareTo(first.lastModifiedEpoch)
                    else -> first.name.compareTo(second.name, ignoreCase = true)
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Column {
                    if (selectedFiles.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = itShareScreenGutter()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { if (currentPath.isNotBlank()) onNavigateUp() else onBackAtRoot() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText(language, "Back", "Kembali"))
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape,
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Box(Modifier.weight(1f)) {
                                        if (searchQuery.isEmpty()) Text(
                                            uiText(language, "Search in ${currentPath.substringAfterLast('/').ifBlank { shareName }}", "Cari di ${currentPath.substringAfterLast('/').ifBlank { shareName }}"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = uiText(language, "Clear search", "Hapus pencarian"), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { showOptionsMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = uiText(language, "More options", "Opsi lainnya"))
                                }
                                DropdownMenu(expanded = showOptionsMenu, onDismissRequest = { showOptionsMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(uiText(language, "Refresh", "Muat ulang")) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = { showOptionsMenu = false; onRefresh() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(uiText(language, "New folder", "Folder baru")) },
                                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                        enabled = !isReadOnly && !transferProgress.isTransferring,
                                        onClick = { showOptionsMenu = false; showCreateFolderDialog = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(uiText(language, "Disconnect", "Putuskan koneksi"), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        enabled = !transferProgress.isTransferring,
                                        onClick = { showOptionsMenu = false; showDisconnectConfirm = true }
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = itShareScreenGutter(), vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                "all" to uiText(language, "All", "Semua"),
                                "images" to uiText(language, "Images", "Gambar"),
                                "documents" to uiText(language, "Documents", "Dokumen"),
                                "video" to uiText(language, "Video", "Video")
                            ).forEach { (key, label) ->
                                val selected = selectedCategory == key
                                Surface(
                                    onClick = { selectedCategory = key },
                                    shape = CircleShape,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Text(
                                        label,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }

                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = itShareScreenGutter(), vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selectedFiles = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = uiText(language, "Clear selection", "Batalkan pilihan"), modifier = Modifier.size(24.dp))
                            }
                            Text(
                                uiText(language, "${selectedFiles.size} selected", "${selectedFiles.size} dipilih"),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                selectedFiles = if (selectedFiles.size == filteredFiles.size) emptySet() else filteredFiles.toSet()
                            }) {
                                Text(uiText(language, "Select all", "Pilih semua"), maxLines = 1, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = itShareScreenGutter(), vertical = if (selectedFiles.isEmpty()) 4.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ItShareBreadcrumbBar(
                            shareName = shareName,
                            currentPath = currentPath,
                            onCrumbClicked = onNavigateToPath,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            TextButton(onClick = { showSortMenu = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(when (sortBy) { "size" -> uiText(language, "Size", "Ukuran"); "date" -> uiText(language, "Date", "Tanggal"); else -> uiText(language, "Name", "Nama") }, maxLines = 1, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf("name" to uiText(language, "Name", "Nama"), "date" to uiText(language, "Date", "Tanggal"), "size" to uiText(language, "Size", "Ukuran")).forEach { (key, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { sortBy = key; showSortMenu = false })
                                }
                            }
                        }
                        IconButton(onClick = {
                            val nextGrid = !isGridView
                            isGridView = nextGrid
                            onExploreViewModeChange(if (nextGrid) "grid" else "list")
                        }) {
                            Icon(
                                if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) uiText(language, "List view", "Tampilan daftar") else uiText(language, "Grid view", "Tampilan kisi"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {

                if (transferProgress.isTransferring) {
                    ItShareTransferMiniDock(
                        progress = transferProgress,
                        onCancel = onCancelTransfer,
                        language = language
                    )
                }

                if (selectedFiles.isNotEmpty()) {
                    Surface(tonalElevation = 0.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                            Row(
                                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SelectionAction(
                                    icon = Icons.Default.Info,
                                    label = uiText(language, "Details", "Properti"),
                                    enabled = selectedFiles.size == 1,
                                    onClick = { fileDetailItem = selectedFiles.singleOrNull() },
                                    modifier = Modifier.weight(1f)
                                )
                                SelectionAction(
                                    icon = Icons.Default.Delete,
                                    label = uiText(language, "Delete", "Hapus"),
                                    enabled = !isReadOnly,
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = { showBulkDeleteDialog = true },
                                    modifier = Modifier.weight(1f)
                                )
                                SelectionAction(
                                    icon = Icons.Default.Download,
                                    label = uiText(language, "Download", "Unduh"),
                                    enabled = !transferProgress.isTransferring && selectedFiles.isNotEmpty(),
                                    onClick = { onDownloadFiles(selectedFiles.toList()); selectedFiles = emptySet() },
                                    modifier = Modifier.weight(1f)
                                )
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
            if (isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(uiText(language, "Loading…", "Memuat…"), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (filteredFiles.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> uiText(language, "No files match \"$searchQuery\"", "Tidak ada berkas yang cocok dengan \"$searchQuery\"")
                            selectedCategory == "images" -> uiText(language, "No images in this folder", "Tidak ada gambar di folder ini")
                            selectedCategory == "video" -> uiText(language, "No videos in this folder", "Tidak ada video di folder ini")
                            selectedCategory == "documents" -> uiText(language, "No documents in this folder", "Tidak ada dokumen di folder ini")
                            else -> uiText(language, "This folder is empty", "Folder ini kosong")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isReadOnly) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(21.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(uiText(language, "Read-only access", "Akses hanya baca"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if (isGridView) {
                        val containerWidth = LocalWindowInfo.current.containerSize.width
                        val screenWidth = with(LocalDensity.current) { containerWidth.toDp().value.toInt() }
                        val gridGutter = itShareScreenGutter()
                        val gridColumns = ((screenWidth - gridGutter.value.toInt() * 2 + 10) / 118).coerceIn(2, 5)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridColumns),
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 900.dp)
                                .fillMaxWidth()
                                .align(Alignment.CenterHorizontally),
                            contentPadding = PaddingValues(start = gridGutter, end = gridGutter, top = 10.dp, bottom = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            gridItems(filteredFiles, key = { "$connectionKey:${it.path}:${it.sizeBytes}:${it.lastModifiedEpoch}" }) { item ->
                                FileGridCard(
                                    item = item,
                                    selected = item in selectedFiles,
                                    selectionMode = selectedFiles.isNotEmpty(),
                                    language = language,
                                    onLoadThumbnail = onLoadThumbnail,
                                    allowThumbnailLoading = !transferProgress.isTransferring,
                                    onClick = { handleItemClick(item) },
                                    onLongClick = { selectedFiles = selectedFiles + item },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 900.dp)
                                .fillMaxWidth()
                                .align(Alignment.CenterHorizontally),
                            contentPadding = PaddingValues(bottom = 100.dp)
                        ) {
                            items(filteredFiles, key = { "$connectionKey:${it.path}:${it.sizeBytes}:${it.lastModifiedEpoch}" }) { item ->
                                val isSelected = item in selectedFiles
                                ItShareFileListItem(
                                    item = item,
                                    isSelected = isSelected,
                                    selectionMode = selectedFiles.isNotEmpty(),
                                    isReadOnly = isReadOnly,
                                    transferInProgress = transferProgress.isTransferring,
                                    onItemClick = { handleItemClick(item) },
                                    onLongClick = { selectedFiles = selectedFiles + item },
                                    language = language,
                                    onLoadThumbnail = onLoadThumbnail,
                                    allowThumbnailLoading = !transferProgress.isTransferring,
                                    onInfoClick = { fileDetailItem = item },
                                    onDownloadClick = { onDownloadFiles(listOf(item)) },
                                    onDeleteClick = { fileToDelete = item },
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(start = 76.dp))
                            }
                        }
                    }
                }
            }
            if (!isLoading && selectedFiles.isEmpty() && !isReadOnly && !transferProgress.isTransferring) {
                ExtendedFloatingActionButton(
                    onClick = onUploadClicked,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(uiText(language, "Upload", "Unggah"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = itShareScreenGutter(), bottom = 20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp)
                )
            }
        }
    }

    fileDetailItem?.let { detail ->
        FileDetailDialog(
            item = detail,
            language = language,
            onDismiss = { fileDetailItem = null }
        )
    }

    mediaPreviewItem?.let { item ->
        key(connectionKey, item.path, item.sizeBytes, item.lastModifiedEpoch) {
            MediaPreviewDialog(
                item = item,
                language = language,
                onDismiss = { mediaPreviewItem = null },
                onLoadImage = onLoadImage,
                onCreateMediaSource = onCreateMediaSource,
                onDownloadClicked = { onDownloadFiles(listOf(item)) }
            )
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(uiText(language, "Disconnect?", "Putuskan koneksi?")) },
            text = { Text(uiText(language, "You can reconnect from Folders later.", "Kamu bisa menyambungkan lagi dari halaman Folder.")) },
            confirmButton = {
                TextButton(onClick = { showDisconnectConfirm = false; onDisconnectClicked() }) { Text(uiText(language, "Disconnect", "Putuskan"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDisconnectConfirm = false }) { Text(uiText(language, "Cancel", "Batal")) } }
        )
    }

    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(uiText(language, "Delete selected items?", "Hapus item yang dipilih?")) },
            text = { Text(uiText(language, "${selectedFiles.size} items will be permanently deleted from the PC.", "${selectedFiles.size} item akan dihapus permanen dari PC.")) },
            confirmButton = {
                TextButton(onClick = {
                    val deleting = selectedFiles.toList()
                    selectedFiles = emptySet()
                    showBulkDeleteDialog = false
                    onDeleteFiles(deleting)
                }) { Text(uiText(language, "Delete", "Hapus"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBulkDeleteDialog = false }) { Text(uiText(language, "Cancel", "Batal")) } }
        )
    }

    fileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(text = if (target.isDirectory) uiText(language, "Delete folder?", "Hapus folder?") else uiText(language, "Delete file?", "Hapus berkas?")) },
            text = {
                Text(
                    text = uiText(language, "Delete \"${target.name}\" from the PC? This cannot be undone.", "Hapus \"${target.name}\" dari PC? Tindakan ini tidak dapat dibatalkan."),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        fileToDelete = null
                        onDeleteFile(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(uiText(language, "Delete", "Hapus"))
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(uiText(language, "Cancel", "Batal"))
                }
            }
        )
    }

    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(uiText(language, "New folder", "Folder baru")) },
            text = {
                Column {
                    Text(
                        text = uiText(language, "Enter a name for the new folder.", "Masukkan nama folder baru."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text(uiText(language, "Folder name", "Nama folder")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newFolderName.trim()
                        if (trimmed.isNotEmpty()) {
                            showCreateFolderDialog = false
                            onCreateFolder(trimmed)
                        }
                    },
                    enabled = newFolderName.isNotBlank()
                ) {
                    Text(uiText(language, "Create", "Buat"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(uiText(language, "Cancel", "Batal"))
                }
            }
        )
    }
}

@Composable
fun ItShareBreadcrumbBar(
    shareName: String,
    currentPath: String,
    onCrumbClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val parts = remember(currentPath) {
        if (currentPath.isBlank()) emptyList() else currentPath.split('/')
    }

    Row(
        modifier = modifier.horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = shareName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.clickable { onCrumbClicked("") }.padding(end = 3.dp)
        )

        var accumulatedPath = ""
        for ((index, part) in parts.withIndex()) {
            accumulatedPath = if (accumulatedPath.isEmpty()) part else "$accumulatedPath/$part"
            val targetPath = accumulatedPath
            val isLast = index == parts.size - 1

            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = part,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clickable { onCrumbClicked(targetPath) }.padding(horizontal = 2.dp)
            )
        }
    }
}

@Composable
fun ItShareFileListItem(
    item: SmbFileItem,
    isSelected: Boolean,
    onItemClick: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    isReadOnly: Boolean = false,
    transferInProgress: Boolean = false,
    onLongClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    language: String = "en",
    onLoadThumbnail: suspend (SmbFileItem, Int) -> Bitmap? = { _, _ -> null },
    allowThumbnailLoading: Boolean = true
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onItemClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileMediaThumbnail(
            item = item,
            selected = isSelected,
            selectionMode = selectionMode,
            allowThumbnailLoading = allowThumbnailLoading,
            onLoadThumbnail = onLoadThumbnail,
            maxThumbnailSize = 256,
            language = language,
            isGrid = false,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            val subtitle = if (item.isDirectory) {
                "Folder"
            } else {
                "${formatFileSize(item.sizeBytes)} · ${formatShortDate(item.lastModifiedEpoch)}"
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = uiText(language, "Options", "Opsi"), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(uiText(language, "Download", "Unduh")) },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    enabled = !transferInProgress,
                    onClick = {
                        showMenu = false
                        onDownloadClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(uiText(language, "File details", "Detail berkas")) },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onInfoClick()
                    }
                )
                if (!isReadOnly) {
                    DropdownMenuItem(
                        text = { Text(uiText(language, "Delete", "Hapus"), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDeleteClick()
                        }
                    )
                }
            }
        }

    }
}

@Composable
private fun FileMediaThumbnail(
    item: SmbFileItem,
    selected: Boolean,
    selectionMode: Boolean,
    allowThumbnailLoading: Boolean,
    onLoadThumbnail: suspend (SmbFileItem, Int) -> Bitmap?,
    maxThumbnailSize: Int,
    language: String,
    isGrid: Boolean,
    modifier: Modifier = Modifier
) {
    var thumbnail by remember(item.path, item.sizeBytes, item.lastModifiedEpoch) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(item.path, item.sizeBytes, item.lastModifiedEpoch, allowThumbnailLoading) {
        if (!allowThumbnailLoading || (!item.isImageFile() && !item.isVideoFile())) return@LaunchedEffect
        val loaded = try {
            onLoadThumbnail(item, maxThumbnailSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (loaded != null) thumbnail = loaded
    }

    Surface(
        shape = RoundedCornerShape(if (isGrid) 16.dp else 12.dp),
        color = if (selectionMode && selected) MaterialTheme.colorScheme.primary else getItemContainerColor(item),
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                selectionMode && selected -> Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (isGrid) 44.dp else 24.dp)
                )
                thumbnail != null -> Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                item.isDirectory -> Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = getItemIconColor(item),
                    modifier = Modifier.size(if (isGrid) 40.dp else 28.dp)
                )
                else -> FileItemGlyph(item, Modifier.size(if (isGrid) 56.dp else 24.dp))
            }

            if (item.isVideoFile() && !(selectionMode && selected)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = uiText(language, "Play video", "Putar video"),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(if (isGrid) 8.dp else 4.dp)
                        .size(if (isGrid) 24.dp else 18.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .padding(if (isGrid) 4.dp else 2.dp)
                )
            }
        }
    }
}

@Composable
private fun FileGridCard(
    item: SmbFileItem,
    selected: Boolean,
    selectionMode: Boolean,
    language: String,
    onLoadThumbnail: suspend (SmbFileItem, Int) -> Bitmap?,
    allowThumbnailLoading: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        FileMediaThumbnail(
            item = item,
            selected = selected,
            selectionMode = selectionMode,
            allowThumbnailLoading = allowThumbnailLoading,
            onLoadThumbnail = onLoadThumbnail,
            maxThumbnailSize = 320,
            language = language,
            isGrid = true,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        )
        Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.isDirectory) uiText(language, "Folder", "Folder") else formatFileSize(item.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
    }
}

@Composable
fun ItShareTransferMiniDock(
    progress: TransferProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    language: String = "en"
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (progress.fileIndex == 0) {
                            if (progress.isDownload) uiText(language, "Preparing download", "Menyiapkan unduhan")
                            else uiText(language, "Preparing upload", "Menyiapkan unggahan")
                        } else {
                            val direction = if (progress.isDownload) uiText(language, "Downloading", "Mengunduh") else uiText(language, "Uploading", "Mengunggah")
                            "$direction (${progress.fileIndex}/${progress.totalFiles})"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = progress.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(uiText(language, "Cancel", "Batal"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (progress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { progress.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (progress.totalBytes > 0L) {
                        "${progress.progressPercent}% • ${formatFileSize(progress.bytesTransferred)} / ${formatFileSize(progress.totalBytes)}"
                    } else {
                        "${formatFileSize(progress.bytesTransferred)} " + uiText(language, "transferred", "terkirim")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatSpeed(progress.speedBytesPerSec),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun FileDetailDialog(
    item: SmbFileItem,
    language: String = "en",
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = uiText(language, "File details", "Detail berkas"),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(label = "${uiText(language, "Name", "Nama")}: ", value = item.name)
                DetailRow(label = "${uiText(language, "Path", "Lokasi")}: ", value = if (item.path.isBlank()) "/" else "/${item.path}")
                DetailRow(label = "${uiText(language, "Type", "Jenis")}: ", value = if (item.isDirectory) uiText(language, "Folder", "Folder") else "${uiText(language, "File", "Berkas")} (${item.name.substringAfterLast('.', "Unknown")})")
                if (!item.isDirectory) {
                    DetailRow(label = "${uiText(language, "Size", "Ukuran")}: ", value = formatFileSize(item.sizeBytes))
                }
                DetailRow(label = "${uiText(language, "Modified", "Diubah")}: ", value = formatDate(item.lastModifiedEpoch))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText(language, "Close", "Tutup"))
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FileItemGlyph(item: SmbFileItem, modifier: Modifier = Modifier) {
    val extension = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (!item.isDirectory && extension in setOf("pdf", "mp4", "mkv", "mov", "zip", "rar", "7z", "txt")) {
        Text(
            text = when (extension) {
                "mkv", "mov" -> "VIDEO"
                "rar", "7z" -> "ZIP"
                else -> extension.uppercase(Locale.ROOT)
            },
            modifier = modifier,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = getItemIconColor(item),
            maxLines = 1
        )
    } else {
        Icon(
            imageVector = getFileIcon(item),
            contentDescription = null,
            tint = getItemIconColor(item),
            modifier = modifier
        )
    }
}

@Composable
private fun getItemContainerColor(item: SmbFileItem): Color {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (item.isDirectory || item.isImageFile()) return if (darkTheme) Color(0xFF214735) else Color(0xFFE3EFEC)
    if (darkTheme) return when (item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> Color(0xFF492624)
        "mp4", "mkv", "mov", "avi" -> Color(0xFF392B4B)
        "zip", "rar", "7z", "tar" -> Color(0xFF493B22)
        "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> Color(0xFF263A56)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    return when (item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> Color(0xFFFCE7E5)
        "mp4", "mkv", "mov", "avi" -> Color(0xFFECE0F7)
        "zip", "rar", "7z", "tar" -> Color(0xFFFFEDC7)
        "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> Color(0xFFDCE9FC)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun getItemIconColor(item: SmbFileItem): Color {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (item.isDirectory || item.isImageFile()) return if (darkTheme) Color(0xFFB2E6D2) else Color(0xFF0F5D52)
    if (darkTheme) return when (item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> Color(0xFFFFB4AB)
        "mp4", "mkv", "mov", "avi" -> Color(0xFFD0BCFF)
        "zip", "rar", "7z", "tar" -> Color(0xFFFFDFA6)
        "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> Color(0xFFB5C9FF)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    return when (item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "pdf" -> Color(0xFFB3261E)
        "mp4", "mkv", "mov", "avi" -> Color(0xFF6034A4)
        "zip", "rar", "7z", "tar" -> Color(0xFF805300)
        "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> Color(0xFF245AA6)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun getFileIcon(item: SmbFileItem): ImageVector = when {
    item.isDirectory -> Icons.Default.Folder
    item.isImageFile() -> Icons.Default.Image
    item.isVideoFile() -> Icons.Default.Movie
    item.name.endsWith(".mp3", ignoreCase = true) ||
            item.name.endsWith(".wav", ignoreCase = true) ||
            item.name.endsWith(".flac", ignoreCase = true) -> Icons.Default.AudioFile
    item.name.endsWith(".pdf", ignoreCase = true) -> Icons.Default.PictureAsPdf
    item.name.endsWith(".zip", ignoreCase = true) ||
            item.name.endsWith(".rar", ignoreCase = true) ||
            item.name.endsWith(".7z", ignoreCase = true) ||
            item.name.endsWith(".tar", ignoreCase = true) -> Icons.Default.Archive
    item.name.endsWith(".txt", ignoreCase = true) ||
            item.name.endsWith(".doc", ignoreCase = true) ||
            item.name.endsWith(".docx", ignoreCase = true) -> Icons.Default.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
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

private fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "-"
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return sdf.format(Date(epochSeconds * 1000))
}

private fun formatShortDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "-"
    val sdf = SimpleDateFormat("dd MMM", Locale.getDefault())
    return sdf.format(Date(epochSeconds * 1000))
}
