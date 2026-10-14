package com.itshare.client.core.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.itshare.client.core.model.ConflictResolution
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.service.TransferForegroundService
import com.itshare.client.core.smb.SmbClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

data class CompletedTransferItem(
    val fileName: String,
    val sizeBytes: Long,
    val isDownload: Boolean,
    val timestampEpoch: Long = System.currentTimeMillis() / 1000,
    val isSuccess: Boolean = true
)

data class CompletedTransferGroup(
    val id: Long = System.currentTimeMillis(),
    val isDownload: Boolean,
    val items: List<CompletedTransferItem>,
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
        get() = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

class TransferManager(
    context: Context,
    private val smbManager: SmbClientManager
) {
    private val context = context.applicationContext
    private val _progressState = MutableStateFlow(TransferProgress())
    val progressState: StateFlow<TransferProgress> = _progressState.asStateFlow()

    private var lastNotificationUpdateMs = 0L

    fun cancelTransfer() {
        TransferCancellationSignal.cancel()
        _progressState.value = _progressState.value.copy(
            isTransferring = false,
            speedBytesPerSec = 0L
        )
    }

    suspend fun downloadFiles(
        items: List<SmbFileItem>,
        targetTreeUri: Uri,
        defaultConflict: ConflictResolution = ConflictResolution.RENAME
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            TransferCancellationSignal.reset()
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

            startService(allFilesToDownload.firstOrNull()?.first?.name ?: "Download", totalBytes)
            try {
                for ((index, pair) in allFilesToDownload.withIndex()) {
                    if (TransferCancellationSignal.isCancelled()) break
                    val item = pair.first

                    if (item.isDirectory) {

                        findOrCreateDir(rootDir, pair.second.ifEmpty { item.name })
                        continue
                    }

                    val parentDirRelative = pair.second.substringBeforeLast('/', "")
                    val targetDir = if (parentDirRelative.isEmpty()) rootDir else findOrCreateDir(rootDir, parentDirRelative)
                    val targetFileName = resolveLocalFileName(targetDir, item.name, defaultConflict) ?: continue

                    val mimeType = getMimeType(targetFileName)
                    val destFile = targetDir.createFile(mimeType, targetFileName)
                        ?: throw IllegalStateException("Gagal membuat berkas lokal: $targetFileName")

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

                    try {
                        val inStream = smbManager.openReadStream(item.path).getOrThrow()
                        try {
                            val outStream = context.contentResolver.openOutputStream(destFile.uri)
                                ?: throw IllegalStateException("Gagal membuka stream tulis lokal")
                            copyStreamWithProgress(inStream, outStream, expectedBytes = item.sizeBytes.takeIf { it > 0L }) { bytesChunk, speed ->
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

                        completedCount++
                        val completedItem = CompletedTransferItem(
                            fileName = item.name,
                            sizeBytes = item.sizeBytes,
                            isDownload = true,
                            isSuccess = true
                        )
                        completedItems += completedItem
                    } catch (error: Throwable) {
                        Log.e("ItShareTransfer", "Download failed for '${item.path}' (${item.sizeBytes} bytes)", error)
                        runCatching { destFile.delete() }
                        if (TransferCancellationSignal.isCancelled()) {
                            throw IllegalStateException("Transfer dibatalkan")
                        }
                        completedItems += CompletedTransferItem(
                            fileName = item.name,
                            sizeBytes = item.sizeBytes,
                            isDownload = true,
                            isSuccess = false
                        )
                    }
                }

                completedCount
            } finally {
                if (completedItems.isNotEmpty()) {
                    val group = CompletedTransferGroup(isDownload = true, items = completedItems.toList())
                    _progressState.value = _progressState.value.copy(recentTransfers = listOf(group) + _progressState.value.recentTransfers.take(19))
                }
                stopService()
                _progressState.value = _progressState.value.copy(isTransferring = false, speedBytesPerSec = 0L)
            }
        }
    }

    suspend fun uploadFiles(
        uris: List<Uri>,
        targetRemoteDir: String,
        defaultConflict: ConflictResolution = ConflictResolution.RENAME
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            TransferCancellationSignal.reset()
            var totalBytes = 0L
            val fileList = mutableListOf<Pair<Uri, Pair<String, Long>>>()
            val completedItems = mutableListOf<CompletedTransferItem>()

            for (uri in uris) {
                val nameAndSize = queryFileNameAndSize(uri)
                totalBytes += nameAndSize.second
                fileList.add(Pair(uri, nameAndSize))
            }

            startService(fileList.firstOrNull()?.second?.first ?: "Upload", totalBytes)
            try {
                var transferredBytesTotal = 0L
                var completedCount = 0

                for ((index, item) in fileList.withIndex()) {
                    if (TransferCancellationSignal.isCancelled()) break
                    val (uri, fileInfo) = item
                    val (origName, fileSize) = fileInfo

                    var targetRemotePath = if (targetRemoteDir.isBlank()) origName else "$targetRemoteDir/$origName"

                    var targetExistedBefore = smbManager.fileExists(targetRemotePath)
                    if (targetExistedBefore) {
                        when (defaultConflict) {
                            ConflictResolution.SKIP -> continue
                            ConflictResolution.OVERWRITE -> {  }
                            ConflictResolution.RENAME -> {
                                val dotIndex = origName.lastIndexOf('.')
                                val baseName = if (dotIndex > 0) origName.substring(0, dotIndex) else origName
                                val ext = if (dotIndex > 0) origName.substring(dotIndex) else ""
                                var counter = 1
                                while (smbManager.fileExists(targetRemotePath)) {
                                    val newName = "$baseName ($counter)$ext"
                                    targetRemotePath = if (targetRemoteDir.isBlank()) newName else "$targetRemoteDir/$newName"
                                    counter++
                                }
                                targetExistedBefore = false
                            }
                        }
                    }

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

                    try {
                        val inStream = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Gagal membuka file input HP")
                        try {

                            val outStream = smbManager.openWriteStream(targetRemotePath).getOrThrow()
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
                    } catch (error: Throwable) {
                        Log.e("ItShareTransfer", "Upload failed for '$origName' ($targetRemotePath)", error)
                        if (!targetExistedBefore) {
                            runCatching { smbManager.deleteFile(targetRemotePath).getOrThrow() }
                        }
                        throw error
                    }

                    completedCount++
                    val completedItem = CompletedTransferItem(
                        fileName = origName,
                        sizeBytes = fileSize,
                        isDownload = false,
                        isSuccess = true
                    )
                    completedItems += completedItem
                }

                completedCount
            } finally {
                if (completedItems.isNotEmpty()) {
                    val group = CompletedTransferGroup(isDownload = false, items = completedItems.toList())
                    _progressState.value = _progressState.value.copy(recentTransfers = listOf(group) + _progressState.value.recentTransfers.take(19))
                }
                stopService()
                _progressState.value = _progressState.value.copy(isTransferring = false, speedBytesPerSec = 0L)
            }
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
        var remainingBytes = expectedBytes
        var lastTime = System.currentTimeMillis()
        var bytesSinceLastTime = 0L
        var currentSpeed = 0L

        inStream.use { input ->
            outStream.use { output ->
                while (true) {
                    val remaining = remainingBytes
                    if (remaining != null && remaining <= 0L) break
                    val bytesToRead = remaining?.let { minOf(buffer.size.toLong(), it).toInt() } ?: buffer.size
                    bytesRead = input.read(buffer, 0, bytesToRead)
                    if (bytesRead == -1) {
                        if (remaining != null && remaining > 0L) {

                            Log.w("ItShareTransfer", "Stream ended ${remaining}B before expected size — accepting as complete")
                        }
                        break
                    }
                    if (TransferCancellationSignal.isCancelled()) break
                    output.write(buffer, 0, bytesRead)
                    remainingBytes = remainingBytes?.minus(bytesRead)
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

    private fun resolveLocalFileName(
        parentDir: DocumentFile,
        originalName: String,
        conflict: ConflictResolution
    ): String? {
        val existing = parentDir.findFile(originalName)
        if (existing == null) return originalName

        return when (conflict) {
            ConflictResolution.SKIP -> null
            ConflictResolution.OVERWRITE -> {
                existing.delete()
                originalName
            }
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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
