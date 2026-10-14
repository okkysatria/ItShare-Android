package com.itshare.client.core.model

data class SmbFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpoch: Long
)

sealed class TransferStatus {
    object Queued : TransferStatus()
    data class InProgress(val bytesTransferred: Long, val totalBytes: Long, val speedBytesPerSec: Long) : TransferStatus()
    object Completed : TransferStatus()
    data class Failed(val error: Throwable) : TransferStatus()
    object Cancelled : TransferStatus()
}

enum class ConflictResolution {
    OVERWRITE,
    SKIP,
    RENAME
}
