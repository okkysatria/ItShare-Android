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
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import android.media.MediaDataSource
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbClientManager {

    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    private var activePayload: SmbPayload? = null
    private val reconnectMutex = Mutex()
    private val openFileMutex = Mutex()

    suspend fun connect(payload: SmbPayload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            reconnectMutex.withLock {
                Log.i("ItShareSMB", "Starting SMB connection to ${payload.host}:${payload.port}/${payload.share}")
                disconnectResources()
                connectResources(payload)
            }
        }.onFailure { error ->
            Log.e("ItShareSMB", "SMB connection stage failed for ${payload.host}:${payload.port}/${payload.share}", error)
        }.onSuccess {
            Log.i("ItShareSMB", "SMB connection completed for ${payload.host}:${payload.port}/${payload.share}")
        }
    }

    suspend fun listFiles(directoryPath: String = ""): Result<List<SmbFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")

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
                        lastModifiedEpoch = info.changeTime.toEpochMillis() / 1000
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
                val input = file.inputStream
                object : FilterInputStream(input) {
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
        }.onFailure { error ->
            Log.e("ItShareSMB", "Failed to open SMB file stream for '$remotePath'", error)
        }
    }

    suspend fun openMediaDataSource(remotePath: String, sizeBytes: Long): Result<MediaDataSource> = withContext(Dispatchers.IO) {
        runCatching {
            withShareRetry {
                val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
                val file = openFileMutex.withLock {
                    share.openFile(
                        toSmbPath(remotePath),
                        EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )
                }
                val resolvedSize = if (sizeBytes > 0) {
                    sizeBytes
                } else {
                    runCatching { file.fileInformation.standardInformation.endOfFile }.getOrDefault(0L)
                }
                SmbMediaDataSource(file, resolvedSize)
            }
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

    suspend fun renameFile(oldPath: String, newPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
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

    suspend fun createDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
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

    suspend fun deleteFile(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = activePayload ?: throw IllegalStateException("Sesi tidak ditemukan")
            if (payload.readOnly) {
                throw IllegalStateException("Sesi hanya baca (Read-Only). Penghapusan ditolak.")
            }
            val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
            share.rm(toSmbPath(remotePath))
        }
    }

    suspend fun deleteDirectory(remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = activePayload ?: throw IllegalStateException("Sesi tidak ditemukan")
            if (payload.readOnly) {
                throw IllegalStateException("Sesi hanya baca (Read-Only). Penghapusan ditolak.")
            }
            val share = diskShare ?: throw IllegalStateException("SMB Share belum terhubung")
            share.rmdir(toSmbPath(remotePath), true)
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

    fun isConnected(): Boolean {
        return diskShare?.isConnected == true && session != null
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
            if (!error.message.orEmpty().contains("DiskShare has already been closed", ignoreCase = true)) {
                throw error
            }

            val payload = activePayload ?: throw error
            disconnectResources()
            connectResources(payload)
            operation()
        }
    }

    private fun SMBApiException.isMissingRemotePath(): Boolean =
        statusCode == 0xC000000FL ||
            statusCode == 0xC0000034L ||
            statusCode == 0xC000003AL
}

private class SmbMediaDataSource(
    private val file: File,
    private val fileSize: Long
) : MediaDataSource() {
    private val lock = Any()
    @Volatile private var closed = false

    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
        if (closed || (fileSize > 0 && position >= fileSize)) return -1
        val targetSize = if (fileSize > 0) {
            minOf(size.toLong(), fileSize - position).toInt()
        } else {
            size
        }
        if (targetSize <= 0) return 0
        var totalRead = 0
        while (totalRead < targetSize) {
            val bytesRead = try {
                file.read(buffer, position + totalRead, offset + totalRead, targetSize - totalRead)
            } catch (error: Exception) {
                if (totalRead > 0) break
                return -1
            }
            if (bytesRead <= 0) break
            totalRead += bytesRead
        }
        return if (totalRead > 0) totalRead else -1
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            try { file.close() } catch (_: Exception) {}
        }
    }
}
