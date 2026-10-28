package com.itshare.client.core.model

data class SmbFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpoch: Long
)

enum class ConflictResolution {
    OVERWRITE,
    SKIP,
    RENAME
}
