package com.itshare.client.core.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.itshare.client.core.model.ConflictResolution
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.service.TransferForegroundService
import com.itshare.client.core.smb.SmbClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

@Serializable
data class CompletedTransferItem(
    val fileName: String,
    val sizeBytes: Long,
    val isDownload: Boolean,
    val timestampEpoch: Long = System.currentTimeMillis() / 1000,
    val isSuccess: Boolean = true,
    val locationPath: String = ""
)

@Serializable
data class CompletedTransferGroup(
    val id: Long = System.currentTimeMillis(),
    val isDownload: Boolean,
    val items: List<CompletedTransferItem>,
    val shareName: String = "",
    val serverAddress: String = "",
    val remoteFolderPath: String = "",
    val localFolderName: String = "",
    val timestampEpoch: Long = System.currentTimeMillis() / 1000
)

data class TransferProgress(
    val isTransferring: Boolean = false,
    val isDownload: Boolean = true,
    val currentFileName: String = "",
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val recentTransfers: List<CompletedTransferGroup> = emptyList()
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) {
            ((bytesTransferred.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0
}

class TransferManager(
    context: Context,
    private val smbManager: SmbClientManager
) {
    private val context = context.applicationContext
    private val historyPreferences = this.context.getSharedPreferences("itshare_transfer_history", Context.MODE_PRIVATE)
    private val historyJson = Json { ignoreUnknownKeys = true }
    private val historyLock = Any()
    private val transferMutex = Mutex()
    private val _progressState = MutableStateFlow(TransferProgress(recentTransfers = loadRecentTransfers()))
    val progressState: StateFlow<TransferProgress> = _progressState.asStateFlow()

    fun isTransferRunning(): Boolean = transferMutex.isLocked

    private var lastNotificationUpdateMs = 0L

    fun cancelTransfer() {
        TransferCancellationSignal.cancel()
        _progressState.value = _progressState.value.copy(
            isTransferring = false,
            speedBytesPerSec = 0L
        )
        stopService()
    }

    suspend fun downloadFiles(
        items: List<SmbFileItem>,
        targetTreeUri: Uri,
        defaultConflict: ConflictResolution = ConflictResolution.RENAME
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!transferMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("Transfer lain sedang berjalan"))
        }
        try {
        runCatching {
            TransferCancellationSignal.reset()
            _progressState.value = TransferProgress(
                isTransferring = true,
                isDownload = true,
                currentFileName = items.firstOrNull()?.name.orEmpty(),
                fileIndex = 0,
                totalFiles = items.size,
                recentTransfers = _progressState.value.recentTransfers
            )
            val rootDir = DocumentFile.fromTreeUri(context, targetTreeUri)
                ?: throw IllegalArgumentException("Direktori tujuan tidak valid atau tidak memiliki akses")

            val normalizedItems = items.distinctBy { it.path.replace('\\', '/').trim('/').lowercase(Locale.ROOT) }
            val selectedDirectories = normalizedItems.filter { it.isDirectory }
            val effectiveItems = normalizedItems.filter { candidate ->
                selectedDirectories.none { directory ->
                    directory.path != candidate.path &&
                        candidate.path.replace('\\', '/').trim('/').startsWith(
                            directory.path.replace('\\', '/').trimEnd('/') + "/",
                            ignoreCase = true
                        )
                }
            }

            val allFilesToDownload = mutableListOf<Pair<SmbFileItem, String>>()
            for (item in effectiveItems) {
                if (item.isDirectory) {
                    val recursiveItems = smbManager.listFilesRecursive(item.path).getOrThrow()
                    allFilesToDownload.add(Pair(item, ""))
                    val selectedRootPrefix = item.path.trimEnd('/') + "/"
                    for (child in recursiveItems) {
                        val relativePath = child.path.removePrefix(selectedRootPrefix)
                        if (relativePath.isNotBlank()) {
                            allFilesToDownload.add(Pair(child, "${item.name}/$relativePath"))
                        }
                    }
                } else {
                    allFilesToDownload.add(Pair(item, item.name))
                }
            }

            val totalBytes = allFilesToDownload.filter { !it.first.isDirectory }.sumOf { it.first.sizeBytes }
            var transferredBytesTotal = 0L
            var completedCount = 0
            val completedItems = mutableListOf<CompletedTransferItem>()
            val failedItems = mutableListOf<Pair<String, Exception>>()

            startService(allFilesToDownload.firstOrNull()?.first?.name ?: "Download", totalBytes)
            try {
                for ((index, pair) in allFilesToDownload.withIndex()) {
                    if (TransferCancellationSignal.isCancelled()) break
                    val item = pair.first

                    if (item.isDirectory) {

                        findOrCreateDir(rootDir, pair.second.ifEmpty { item.name })
                        continue
                    }

                    _progressState.value = TransferProgress(
                        isTransferring = true,
                        isDownload = true,
                        currentFileName = item.name,
                        bytesTransferred = transferredBytesTotal,
                        totalBytes = totalBytes,
                        fileIndex = index + 1,
                        totalFiles = allFilesToDownload.size,
                        recentTransfers = _progressState.value.recentTransfers
                    )

                    val parentDirRelative = pair.second.substringBeforeLast('/', "")
                    val targetDir = if (parentDirRelative.isEmpty()) rootDir else findOrCreateDir(rootDir, parentDirRelative)
                    val targetFileName = resolveLocalFileName(targetDir, item.name, defaultConflict) ?: continue
                    val previousTarget = if (defaultConflict == ConflictResolution.OVERWRITE) {
                        targetDir.findFile(targetFileName)
                    } else null
                    if (previousTarget?.isDirectory == true) {
                        throw IllegalStateException("Tidak dapat menimpa folder dengan berkas: $targetFileName")
                    }

                    val mimeType = getMimeType(targetFileName)
                    val stagedName = if (previousTarget != null) ".itshare-part-${System.nanoTime()}" else targetFileName
                    val destFile = targetDir.createFile(mimeType, stagedName)
                        ?: throw IllegalStateException("Gagal membuat berkas lokal: $targetFileName")

                    try {
                        val inStream = smbManager.openReadStream(item.path).getOrThrow()
                        try {
                            val outStream = context.contentResolver.openOutputStream(destFile.uri)
                                ?: throw IllegalStateException("Gagal membuka stream tulis lokal")
                            copyStreamWithProgress(inStream, outStream, expectedBytes = item.sizeBytes.takeIf { it >= 0L }) { bytesChunk, speed ->
                                transferredBytesTotal += bytesChunk
                                _progressState.value = _progressState.value.copy(
                                    bytesTransferred = transferredBytesTotal,
                                    speedBytesPerSec = speed
                                )
                                updateServiceProgress(item.name, totalBytes, transferredBytesTotal, speed)
                            }
                        } finally {

                            runCatching { inStream.close() }
                        }
                        if (TransferCancellationSignal.isCancelled()) throw IllegalStateException("Transfer dibatalkan")

                        if (previousTarget != null) {
                            val backupName = ".itshare-backup-${System.nanoTime()}"
                            if (!previousTarget.renameTo(backupName)) {
                                throw IllegalStateException("Tidak dapat mengamankan berkas lama sebelum menimpa: $targetFileName")
                            }
                            if (!destFile.renameTo(targetFileName)) {
                                val backup = targetDir.findFile(backupName)
                                val restored = backup?.renameTo(targetFileName) == true
                                if (!restored) {
                                    Log.e("ItShareTransfer", "Berkas lama tersimpan sebagai '$backupName' karena pemulihan nama gagal")
                                }
                                throw IllegalStateException("Tidak dapat mengganti berkas lama: $targetFileName")
                            }
                            targetDir.findFile(backupName)?.let { backup ->
                                if (!backup.delete()) Log.w("ItShareTransfer", "Berkas cadangan sementara tertinggal: '$backupName'")
                            }
                        }

                        completedCount++
                        val completedItem = CompletedTransferItem(
                            fileName = item.name,
                            sizeBytes = item.sizeBytes,
                            isDownload = true,
                            isSuccess = true,
                            locationPath = item.path
                        )
                        completedItems += completedItem
                    } catch (error: Exception) {
                        Log.e("ItShareTransfer", "Download failed for '${item.path}' (${item.sizeBytes} bytes)", error)
                        if (!runCatching { destFile.delete() }.getOrDefault(false)) {
                            Log.e("ItShareTransfer", "Could not remove partial download '${destFile.name}'")
                        }
                        if (TransferCancellationSignal.isCancelled()) {
                            throw IllegalStateException("Transfer dibatalkan")
                        }
                        completedItems += CompletedTransferItem(
                            fileName = item.name,
                            sizeBytes = item.sizeBytes,
                            isDownload = true,
                            isSuccess = false,
                            locationPath = item.path
                        )
                        failedItems += item.name to error
                    }
                }

                if (failedItems.isNotEmpty()) {
                    val firstFailure = failedItems.first()
                    throw java.io.IOException(
                        "${failedItems.size} berkas gagal diunduh; ${firstFailure.first}: ${firstFailure.second.localizedMessage ?: "kesalahan SMB"}",
                        firstFailure.second
                    )
                }
                completedCount
            } finally {
                if (completedItems.isNotEmpty()) {
                    val group = createTransferGroup(
                        isDownload = true,
                        items = completedItems.toList(),
                        localFolderName = rootDir.name.orEmpty()
                    )
                    recordCompletedGroup(group)
                }
                stopService()
                _progressState.value = _progressState.value.copy(isTransferring = false, speedBytesPerSec = 0L)
            }
        }.onFailure {
            _progressState.value = _progressState.value.copy(isTransferring = false, speedBytesPerSec = 0L)
        }
        } finally {
            transferMutex.unlock()
        }
    }

    suspend fun uploadFiles(
        uris: List<Uri>,
        targetRemoteDir: String,
        defaultConflict: ConflictResolution = ConflictResolution.RENAME
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!transferMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("Transfer lain sedang berjalan"))
        }
        try {
        runCatching {
            TransferCancellationSignal.reset()
            _progressState.value = TransferProgress(
                isTransferring = true,
                isDownload = false,
                currentFileName = "Menyiapkan unggahan",
                fileIndex = 0,
                totalFiles = uris.size,
                recentTransfers = _progressState.value.recentTransfers
            )
            var totalBytes = 0L
            val fileList = mutableListOf<Pair<Uri, Pair<String, Long>>>()
            val completedItems = mutableListOf<CompletedTransferItem>()

            for (uri in uris) {
                val nameAndSize = queryFileNameAndSize(uri)
                totalBytes += nameAndSize.second
                fileList.add(Pair(uri, nameAndSize))
            }

            var transferServiceStarted = false
            try {
                var transferredBytesTotal = 0L
                var completedCount = 0

                for ((index, item) in fileList.withIndex()) {
                    if (TransferCancellationSignal.isCancelled()) break
                    val (uri, fileInfo) = item
                    val (origName, fileSize) = fileInfo

                    _progressState.value = TransferProgress(
                        isTransferring = true,
                        isDownload = false,
                        currentFileName = origName,
                        bytesTransferred = transferredBytesTotal,
                        totalBytes = totalBytes,
                        fileIndex = index + 1,
                        totalFiles = fileList.size,
                        recentTransfers = _progressState.value.recentTransfers
                    )

                    var targetRemotePath = if (targetRemoteDir.isBlank()) origName else "$targetRemoteDir/$origName"

                    val targetIsDirectory = smbManager.directoryExists(targetRemotePath)
                    if (targetIsDirectory && defaultConflict == ConflictResolution.OVERWRITE) {
                        throw IllegalArgumentException("Tidak dapat menimpa folder dengan berkas: $origName")
                    }
                    var targetExistedBefore = targetIsDirectory || smbManager.fileExists(targetRemotePath)
                    if (targetExistedBefore) {
                        when (defaultConflict) {
                            ConflictResolution.SKIP -> continue
                            ConflictResolution.OVERWRITE -> {  }
                            ConflictResolution.RENAME -> {
                                val dotIndex = origName.lastIndexOf('.')
                                val baseName = if (dotIndex > 0) origName.substring(0, dotIndex) else origName
                                val ext = if (dotIndex > 0) origName.substring(dotIndex) else ""
                                var counter = 1
                                while (smbManager.fileExists(targetRemotePath) || smbManager.directoryExists(targetRemotePath)) {
                                    val newName = "$baseName ($counter)$ext"
                                    targetRemotePath = if (targetRemoteDir.isBlank()) newName else "$targetRemoteDir/$newName"
                                    counter++
                                }
                                targetExistedBefore = false
                            }
                        }
                    }
                    val uploadPath = if (targetExistedBefore && defaultConflict == ConflictResolution.OVERWRITE) {
                        remoteSiblingPath(targetRemoteDir, ".itshare-part-${System.nanoTime()}")
                    } else targetRemotePath

                    try {
                        val inStream = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Gagal membuka file input HP")
                        try {

                            val outStream = smbManager.openWriteStream(uploadPath).getOrThrow()
                            if (!transferServiceStarted) {
                                startService(origName, totalBytes)
                                transferServiceStarted = true
                            }
                            copyStreamWithProgress(
                                inStream,
                                outStream,
                                expectedBytes = fileSize.takeIf { it > 0L }
                            ) { bytesChunk, speed ->
                                transferredBytesTotal += bytesChunk
                                _progressState.value = _progressState.value.copy(
                                    bytesTransferred = transferredBytesTotal,
                                    speedBytesPerSec = speed
                                )
                                updateServiceProgress(origName, totalBytes, transferredBytesTotal, speed)
                            }
                        } finally {
                            runCatching { inStream.close() }
                        }
                        if (TransferCancellationSignal.isCancelled()) throw IllegalStateException("Transfer dibatalkan")
                        if (uploadPath != targetRemotePath) {
                            commitRemoteOverwrite(uploadPath, targetRemotePath)
                        }
                    } catch (error: Exception) {
                        Log.e("ItShareTransfer", "Upload failed for '$origName' ($targetRemotePath)", error)
                        val cleanupPath = if (uploadPath != targetRemotePath || !targetExistedBefore) uploadPath else null
                        cleanupPath?.let { path ->
                            smbManager.deleteFile(path).onFailure { cleanupError ->
                                Log.e("ItShareTransfer", "Could not remove partial upload '$path'", cleanupError)
                            }
                        }
                        completedItems += CompletedTransferItem(
                            fileName = origName,
                            sizeBytes = fileSize,
                            isDownload = false,
                            isSuccess = false,
                            locationPath = targetRemotePath
                        )
                        throw error
                    }

                    completedCount++
                    val completedItem = CompletedTransferItem(
                        fileName = origName,
                        sizeBytes = fileSize,
                        isDownload = false,
                        isSuccess = true,
                        locationPath = targetRemotePath
                    )
                    completedItems += completedItem
                }

                completedCount
            } finally {
                if (completedItems.isNotEmpty()) {
                    val group = createTransferGroup(
                        isDownload = false,
                        items = completedItems.toList(),
                        remoteFolderPath = targetRemoteDir
                    )
                    recordCompletedGroup(group)
                }
                if (transferServiceStarted) stopService()
                _progressState.value = _progressState.value.copy(isTransferring = false, speedBytesPerSec = 0L)
            }
        }.onFailure {
            _progressState.value = _progressState.value.copy(isTransferring = false, speedBytesPerSec = 0L)
        }
        } finally {
            transferMutex.unlock()
        }
    }

    suspend fun findUploadConflicts(uris: List<Uri>, targetRemoteDir: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val names = uris.map { queryFileNameAndSize(it).first }
                val duplicateNames = names.groupingBy { it.lowercase(Locale.ROOT) }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                val conflicts = names.filter { name ->
                    name.lowercase(Locale.ROOT) in duplicateNames ||
                        smbManager.fileExists(if (targetRemoteDir.isBlank()) name else "$targetRemoteDir/$name")
                }
                conflicts.distinct()
            }
        }

    private fun copyStreamWithProgress(
        inStream: InputStream,
        outStream: OutputStream,
        expectedBytes: Long? = null,
        onChunk: (bytesRead: Int, speedBps: Long) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int
        var copiedBytes = 0L
        var lastTime = System.currentTimeMillis()
        var bytesSinceLastTime = 0L
        var currentSpeed = 0L

        inStream.use { input ->
            outStream.use { output ->
                while (true) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) {
                        break
                    }
                    if (bytesRead == 0) continue
                    if (TransferCancellationSignal.isCancelled()) break
                    output.write(buffer, 0, bytesRead)
                    copiedBytes += bytesRead
                    bytesSinceLastTime += bytesRead

                    val now = System.currentTimeMillis()
                    val delta = now - lastTime
                    if (delta >= 500) {
                        currentSpeed = (bytesSinceLastTime * 1000) / delta
                        lastTime = now
                        bytesSinceLastTime = 0
                    }
                    onChunk(bytesRead, currentSpeed)
                }
                output.flush()
            }
        }
        if (!TransferCancellationSignal.isCancelled() && expectedBytes != null && copiedBytes != expectedBytes) {
            val difference = copiedBytes - expectedBytes
            val message = if (difference < 0L) {
                "Stream berakhir sebelum lengkap: diterima $copiedBytes dari $expectedBytes byte"
            } else {
                "Ukuran stream berubah: diterima $copiedBytes, seharusnya $expectedBytes byte"
            }
            throw java.io.EOFException(message)
        }
    }

    private fun loadRecentTransfers(): List<CompletedTransferGroup> = synchronized(historyLock) {
        val serialized = historyPreferences.getString(KEY_RECENT_TRANSFERS, null) ?: return@synchronized emptyList()
        runCatching {
            historyJson.decodeFromString(ListSerializer(CompletedTransferGroup.serializer()), serialized)
                .take(MAX_RECENT_GROUPS)
        }.onFailure { error ->
            Log.w("ItShareTransfer", "Riwayat transfer lokal tidak dapat dibaca; mulai dengan riwayat kosong", error)
        }.getOrDefault(emptyList())
    }

    private fun createTransferGroup(
        isDownload: Boolean,
        items: List<CompletedTransferItem>,
        remoteFolderPath: String = "",
        localFolderName: String = ""
    ): CompletedTransferGroup {
        val payload = smbManager.getActivePayload()
        return CompletedTransferGroup(
            isDownload = isDownload,
            items = items,
            shareName = payload?.share.orEmpty(),
            serverAddress = payload?.let { "${it.host}:${it.port}" }.orEmpty(),
            remoteFolderPath = remoteFolderPath,
            localFolderName = localFolderName
        )
    }

    private fun recordCompletedGroup(group: CompletedTransferGroup) = synchronized(historyLock) {
        val updated = (listOf(group) + _progressState.value.recentTransfers).take(MAX_RECENT_GROUPS)
        _progressState.value = _progressState.value.copy(recentTransfers = updated)
        runCatching {
            historyPreferences.edit(commit = true) {
                putString(KEY_RECENT_TRANSFERS, historyJson.encodeToString(ListSerializer(CompletedTransferGroup.serializer()), updated))
            }
        }.onFailure { error ->
            Log.e("ItShareTransfer", "Gagal menyimpan riwayat transfer", error)
        }
    }

    private companion object {
        const val KEY_RECENT_TRANSFERS = "recent_transfers_v1"
        const val MAX_RECENT_GROUPS = 100
    }

    private fun findOrCreateDir(parent: DocumentFile, relativePath: String): DocumentFile {
        val parts = relativePath.split('/').filter { it.isNotBlank() }
        var current = parent
        for (part in parts) {
            val existing = current.findFile(part)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(part) ?: throw IllegalStateException("Gagal membuat folder lokal: $part")
            }
        }
        return current
    }

    private suspend fun commitRemoteOverwrite(stagedPath: String, targetPath: String) {
        val parentPath = targetPath.substringBeforeLast('/', "")
        val backupPath = remoteSiblingPath(parentPath, ".itshare-backup-${System.nanoTime()}")
        smbManager.renameFile(targetPath, backupPath).getOrThrow()
        val installResult = smbManager.renameFile(stagedPath, targetPath)
        if (installResult.isFailure) {
            val restoreResult = smbManager.renameFile(backupPath, targetPath)
            if (restoreResult.isFailure) {
                Log.e("ItShareTransfer", "Could not restore original '$targetPath'; backup remains at '$backupPath'", restoreResult.exceptionOrNull())
            }
            throw installResult.exceptionOrNull() ?: IllegalStateException("Tidak dapat mengganti berkas tujuan: $targetPath")
        }
        smbManager.deleteFile(backupPath).onFailure { error ->
            Log.w("ItShareTransfer", "Temporary overwrite backup remains at '$backupPath'", error)
        }
    }

    private fun remoteSiblingPath(parentPath: String, name: String): String =
        if (parentPath.isBlank()) name else "$parentPath/$name"

    private fun resolveLocalFileName(
        parentDir: DocumentFile,
        originalName: String,
        conflict: ConflictResolution
    ): String? {
        return when (parentDir.findFile(originalName)) {
            null -> originalName
            else -> when (conflict) {
                ConflictResolution.SKIP -> null
                ConflictResolution.OVERWRITE -> originalName
                ConflictResolution.RENAME -> {
                    val dotIndex = originalName.lastIndexOf('.')
                    val baseName = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
                    val ext = if (dotIndex > 0) originalName.substring(dotIndex) else ""
                    var counter = 1
                    while (parentDir.findFile("$baseName ($counter)$ext") != null) {
                        counter++
                    }
                    "$baseName ($counter)$ext"
                }
            }
        }
    }

    private fun queryFileNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    private fun startService(fileName: String, totalBytes: Long) {
        val intent = Intent(context, TransferForegroundService::class.java).apply {
            putExtra(TransferForegroundService.EXTRA_FILE_NAME, fileName)
            putExtra(TransferForegroundService.EXTRA_TOTAL_BYTES, totalBytes)
        }
        context.startForegroundService(intent)
    }

    private fun updateServiceProgress(fileName: String, totalBytes: Long, transferredBytes: Long, speedBytesPerSec: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs < 500 && transferredBytes < totalBytes) return
        lastNotificationUpdateMs = now
        val intent = Intent(context, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_UPDATE_PROGRESS
            putExtra(TransferForegroundService.EXTRA_FILE_NAME, fileName)
            putExtra(TransferForegroundService.EXTRA_TOTAL_BYTES, totalBytes)
            putExtra(TransferForegroundService.EXTRA_BYTES_TRANSFERRED, transferredBytes)
            putExtra(TransferForegroundService.EXTRA_SPEED_BYTES_PER_SEC, speedBytesPerSec)
        }
        // The transfer already started this service as a foreground service.
        // Progress updates must not create a fresh foreground-start deadline.
        runCatching { context.startService(intent) }
            .onFailure { Log.w("ItShareTransfer", "Could not update transfer notification", it) }
    }

    private fun stopService() {
        try {
            val intent = Intent(context, TransferForegroundService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e("ItShareTransfer", "Failed to stop TransferForegroundService", e)
        }
    }
}
