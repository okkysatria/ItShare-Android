package com.itshare.client.core.smb

import android.util.Log
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.itshare.client.core.model.SmbFileItem
import com.itshare.client.core.model.SmbPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.io.IOException
import android.media.MediaDataSource
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SmbClientManager {

    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    private var activePayload: SmbPayload? = null
    private val reconnectMutex = Mutex()
    private val openFileMutex = Mutex()
    // SMB reads on this host can return malformed packets when several handles are
    // read at once. Keep each response/request pair serialized across all readers.
    private val smbReadLock = ReentrantLock()

    suspend fun connect(payload: SmbPayload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            reconnectMutex.withLock {
                Log.i("ItShareSMB", "Starting SMB connection to ${payload.host}:${payload.port}/${payload.share}")
                disconnectResources()
                var lastError: Exception? = null
                for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
                    try {
                        connectResources(payload)
                        lastError = null
                        break
                    } catch (error: Exception) {
                        lastError = error
                        disconnectResources()
                        if (!isRecoverableConnectionError(error) || attempt == MAX_CONNECT_ATTEMPTS) throw error
                        delay((CONNECT_RETRY_DELAY_MS * attempt).milliseconds)
                    }
                }
                lastError?.let { throw it }
                Unit
            }
        }.onFailure { error ->
            Log.e("ItShareSMB", "SMB connection stage failed for ${payload.host}:${payload.port}/${payload.share}", error)
        }.onSuccess {
            Log.i("ItShareSMB", "SMB connection completed for ${payload.host}:${payload.port}/${payload.share}")
        }
    }

    suspend fun listFiles(directoryPath: String = ""): Result<List<SmbFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val logicalDirectory = normalizeLogicalPath(directoryPath)
            Log.d("ItShareSMB", "Listing share path='${toSmbPath(logicalDirectory)}'")
            val rawItems = withShareRetry {
                val activeShare = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                activeShare.list(toSmbPath(logicalDirectory))
            }

            rawItems
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info: FileIdBothDirectoryInformation ->
                    val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    val itemPath = normalizeLogicalPath(
                        if (logicalDirectory.isBlank()) info.fileName else "$logicalDirectory/${info.fileName}"
                    )

                    SmbFileItem(
                        name = info.fileName,
                        path = itemPath,
                        isDirectory = isDir,
                        sizeBytes = info.endOfFile,
                        lastModifiedEpoch = info.lastWriteTime.toEpochMillis() / 1000
                    )
                }
                .sortedWith(compareByDescending<SmbFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }.onFailure { error ->
            Log.e("ItShareSMB", "Directory listing failed for '$directoryPath'", error)
        }
    }

    suspend fun openReadStream(remotePath: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            withShareRetry {
                val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                val logicalPath = normalizeLogicalPath(remotePath)
                Log.d("ItShareSMB", "Opening read stream for '$logicalPath'")
                val file = openFileMutex.withLock {
                    share.openFile(
                        toSmbPath(logicalPath),
                        EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )
                }
                val fileSize = runCatching { file.fileInformation.standardInformation.endOfFile }
                    .getOrDefault(-1L)
                SmbReadInputStream(logicalPath, file, fileSize, smbReadLock) { openMediaFile(logicalPath) }
            }
        }.onFailure { error ->
            Log.e("ItShareSMB", "Failed to open SMB file stream for '$remotePath'", error)
        }
    }

    suspend fun openMediaDataSource(remotePath: String, sizeBytes: Long): Result<MediaDataSource> = withContext(Dispatchers.IO) {
        runCatching {
            val logicalPath = normalizeLogicalPath(remotePath)
            val file = openMediaFile(logicalPath)
            // The listing can be stale if the file changed after the directory was read.
            // MediaDataSource uses this value to cap every random read, so prefer the
            // size returned with the freshly opened handle.
            val actualSize = file.fileInformation.standardInformation.endOfFile
            val resolvedSize = actualSize.takeIf { it > 0L } ?: sizeBytes
            require(resolvedSize > 0L) { "Ukuran berkas video tidak valid atau berkas kosong" }
            SmbMediaDataSource(file, resolvedSize, smbReadLock) { openMediaFile(logicalPath) }
        }
    }

    private suspend fun openMediaFile(logicalPath: String): File = withShareRetry {
        val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
        openFileMutex.withLock {
            share.openFile(
                toSmbPath(logicalPath),
                EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        }
    }

    suspend fun openWriteStream(remotePath: String): Result<OutputStream> = withContext(Dispatchers.IO) {
        runCatching {
            withShareRetry {
                val payload = activePayload ?: throw IllegalStateException("Sesi tidak ditemukan")
                if (payload.readOnly) {
                    throw IllegalStateException("Sesi hanya baca (Read-Only). Upload ditolak.")
                }
                val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                val file = openFileMutex.withLock {
                    share.openFile(
                        toSmbPath(remotePath),
                        EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        null
                    )
                }
                val output = file.outputStream
                object : FilterOutputStream(output) {
                    private var released = false
                    override fun close() {
                        if (released) return
                        released = true
                        try {
                            super.close()
                        } finally {
                            runCatching { file.close() }
                        }
                    }
                }
            }
        }
    }

    suspend fun fileExists(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        withShareRetry {
            val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
            try {
                if (share.fileExists(toSmbPath(remotePath))) return@withShareRetry true
            } catch (error: SMBApiException) {
                if (!error.isMissingRemotePath()) throw error
            }
            try {
                share.folderExists(toSmbPath(remotePath))
            } catch (error: SMBApiException) {
                if (error.isMissingRemotePath()) false else throw error
            }
        }
    }

    suspend fun directoryExists(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        withShareRetry {
            val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
            try {
                share.folderExists(toSmbPath(remotePath))
            } catch (error: SMBApiException) {
                if (error.isMissingRemotePath()) false else throw error
            }
        }
    }

    suspend fun renameFile(oldPath: String, newPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withShareRetry {
                val payload = activePayload ?: throw IllegalStateException("Sesi tidak ditemukan")
                if (payload.readOnly) throw IllegalStateException("Sesi hanya baca.")
                val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                val file = share.openFile(
                    toSmbPath(oldPath),
                    EnumSet.of(com.hierynomus.msdtyp.AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                file.use { it.rename(toSmbPath(newPath), false) }
            }
        }
    }

    suspend fun createDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withShareRetry {
                val payload = activePayload ?: throw IllegalStateException("Sesi tidak ditemukan")
                if (payload.readOnly) {
                    throw IllegalStateException("Sesi hanya baca (Read-Only).")
                }
                val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                val smbPath = toSmbPath(remotePath)
                if (!share.folderExists(smbPath)) {
                    share.mkdir(smbPath)
                }
            }
        }
    }

    suspend fun deleteFile(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withShareRetry {
                val payload = activePayload ?: throw IllegalStateException("Sesi tidak ditemukan")
                if (payload.readOnly) {
                    throw IllegalStateException("Sesi hanya baca (Read-Only). Penghapusan ditolak.")
                }
                val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                share.rm(toSmbPath(remotePath))
            }
        }
    }

    suspend fun deleteDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            withShareRetry {
                val payload = activePayload ?: throw IllegalStateException("Sesi tidak ditemukan")
                if (payload.readOnly) {
                    throw IllegalStateException("Sesi hanya baca (Read-Only). Penghapusan ditolak.")
                }
                val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                share.rmdir(toSmbPath(remotePath), true)
            }
        }
    }

    suspend fun listFilesRecursive(directoryPath: String): Result<List<SmbFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = mutableListOf<SmbFileItem>()
            suspend fun scan(dir: String) {
                val items = listFiles(dir).getOrThrow()
                for (item in items) {
                    result.add(item)
                    if (item.isDirectory) {
                        scan(item.path)
                    }
                }
            }
            scan(directoryPath)
            result
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        reconnectMutex.withLock { disconnectResources() }
    }

    private fun disconnectResources() {
        try {
            diskShare?.close()
        } catch (_: Exception) {}
        try {
            session?.close()
        } catch (_: Exception) {}
        try {
            connection?.close()
        } catch (_: Exception) {}
        try {
            smbClient?.close()
        } catch (_: Exception) {}

        diskShare = null
        session = null
        connection = null
        smbClient = null
        activePayload = null
    }

    private fun connectResources(payload: SmbPayload) {
        val config = SmbConfig.builder()
            .withTimeout(5, TimeUnit.SECONDS)
            .withSoTimeout(10, TimeUnit.SECONDS)
            .build()

        val client = SMBClient(config)
        smbClient = client

        Log.d("ItShareSMB", "Opening TCP/SMB socket to ${payload.host}:${payload.port}")
        val conn = client.connect(payload.host, payload.port)
        connection = conn
        Log.d("ItShareSMB", "TCP/SMB negotiation completed; starting NTLM authentication")

        val authContext = AuthenticationContext(payload.user.trim(), payload.pass.toCharArray(), "")
        val sess = conn.authenticate(authContext)
        session = sess
        Log.d("ItShareSMB", "NTLM authentication succeeded; opening share ${payload.share}")

        diskShare = sess.connectShare(payload.share) as DiskShare
        activePayload = payload
        Log.i("ItShareSMB", "Share ${payload.share} opened successfully")
    }

    fun getActivePayload(): SmbPayload? = activePayload

    private fun toSmbPath(path: String): String {
        val parts = normalizeLogicalPath(path).split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "Path tidak boleh keluar dari folder share." }
        return parts.joinToString("\\")
    }

    private fun normalizeLogicalPath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Path tidak boleh keluar dari folder share." }
        return parts.joinToString("/")
    }

    private suspend fun <T> withShareRetry(operation: suspend () -> T): T = reconnectMutex.withLock {
        try {
            operation()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (!isRecoverableConnectionError(error)) throw error

            val payload = activePayload ?: throw error
            Log.w("ItShareSMB", "SMB connection appears stale; reconnecting to ${payload.host}:${payload.port}/${payload.share}", error)
            disconnectResources()
            connectResources(payload)
            operation()
        }
    }

    private fun isRecoverableConnectionError(error: Exception): Boolean {
        if (diskShare?.isConnected == false) return true
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException) return true
            val message = current.message.orEmpty()
            if (listOf(
                    "already been closed", "connection reset", "connection refused", "failed to connect",
                    "broken pipe", "socket is closed", "timed out", "transport is closed", "no route to host", "unreachable"
                )
                    .any { message.contains(it, ignoreCase = true) }) return true
            if (current is SMBApiException && current.statusCode in setOf(
                    0xC00000C9L, // STATUS_NETWORK_NAME_DELETED
                    0xC0000203L, // STATUS_USER_SESSION_DELETED
                    0xC000020CL, // STATUS_CONNECTION_DISCONNECTED
                    0xC000035CL  // STATUS_NETWORK_SESSION_EXPIRED
                )) return true
            current = current.cause
        }
        return false
    }

    private fun SMBApiException.isMissingRemotePath(): Boolean =
        statusCode == 0xC000000FL ||
            statusCode == 0xC0000034L ||
            statusCode == 0xC000003AL

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY_MS = 750L
    }
}

private class SmbMediaDataSource(
    initialFile: File,
    private val fileSize: Long,
    private val smbReadLock: ReentrantLock,
    private val reopenFile: suspend () -> File
) : MediaDataSource() {
    private val lock = Any()
    @Volatile private var closed = false
    private var file = initialFile

    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        smbReadLock.withLock {
            synchronized(lock) {
                if (closed || fileSize in 1L..position) return -1
                val targetSize = if (fileSize > 0) {
                    minOf(size.toLong(), fileSize - position).toInt()
                } else {
                    size
                }
                if (targetSize <= 0) return 0
                if (position < 0L || offset < 0 || size < 0 || offset > buffer.size - size) {
                    throw IndexOutOfBoundsException("Invalid media read range")
                }
                var totalRead = 0
                var reopenAttempted = false
                while (totalRead < targetSize) {
                    val bytesRead = try {
                        file.read(buffer, position + totalRead, offset + totalRead, targetSize - totalRead)
                    } catch (readError: Exception) {
                        if (reopenAttempted || closed) return if (totalRead > 0) totalRead else -1
                        reopenAttempted = true
                        val previousFile = file
                        file = try {
                            kotlinx.coroutines.runBlocking(Dispatchers.IO) { reopenFile() }
                        } catch (reopenError: Exception) {
                            Log.w("ItShareSMB", "Could not reopen media handle after a read failure", reopenError)
                            return if (totalRead > 0) totalRead else -1
                        }
                        runCatching { previousFile.close() }
                        Log.d("ItShareSMB", "Reopened SMB media handle after read failure: ${readError.message}")
                        continue
                    }
                    if (bytesRead <= 0) break
                    totalRead += bytesRead
                }
                if (totalRead > 0) totalRead else -1
            }
        }

    override fun close() = smbReadLock.withLock {
        synchronized(lock) {
            if (!closed) {
                closed = true
                try { file.close() } catch (_: Exception) {}
            }
        }
    }
}

/** Random-access SMB reads give us a stable offset to resume from after a broken response. */
private class SmbReadInputStream(
    private val remotePath: String,
    initialFile: File,
    private val fileSize: Long,
    private val smbReadLock: ReentrantLock,
    private val reopenFile: suspend () -> File
) : InputStream() {
    private val stateLock = Any()
    @Volatile private var closed = false
    private var file = initialFile
    private var position = 0L
    private val singleByte = ByteArray(1)

    override fun read(): Int {
        val count = read(singleByte, 0, 1)
        return if (count < 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = smbReadLock.withLock {
        synchronized(stateLock) {
            if (closed) throw IOException("SMB input stream is closed")
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException("Invalid SMB read range")
            }
            if (length == 0) return 0
            if (fileSize >= 0L && position >= fileSize) return -1
            val requested = if (fileSize >= 0L) minOf(length.toLong(), fileSize - position).toInt() else length
            val bytesRead = try {
                file.read(buffer, position, offset, requested)
            } catch (readError: Exception) {
                Log.w("ItShareSMB", "Retrying SMB read at byte $position for '$remotePath'", readError)
                val previousFile = file
                file = try {
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) { reopenFile() }
                } catch (reopenError: Exception) {
                    readError.addSuppressed(reopenError)
                    throw IOException("Could not reopen SMB file after a read failure", readError)
                }
                runCatching { previousFile.close() }
                try {
                    file.read(buffer, position, offset, requested)
                } catch (retryError: Exception) {
                    retryError.addSuppressed(readError)
                    throw IOException("SMB read failed after reopening the file", retryError)
                }
            }
            if (bytesRead <= 0) return -1
            position += bytesRead
            bytesRead
        }
    }

    override fun close() = smbReadLock.withLock {
        synchronized(stateLock) {
            if (!closed) {
                closed = true
                runCatching { file.close() }
            }
        }
    }
}
