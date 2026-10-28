package com.itshare.client.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SmbPayload(
    @SerialName("v") val version: Int,
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
    @SerialName("share") val share: String,
    @SerialName("user") val user: String,
    @SerialName("pass") val pass: String,
    @SerialName("ro") val readOnly: Boolean = false,
    @SerialName("exp") val expEpoch: Long? = null,
    @kotlinx.serialization.Transient val lastConnectedEpoch: Long = 0L
) {

    fun isExpired(currentTimeEpoch: Long = System.currentTimeMillis() / 1000): Boolean =
        expEpoch?.takeIf { it != 0L }?.let { currentTimeEpoch > it + 180 } ?: false

    fun validate(): Result<Unit> {
        if (version != 1) {
            return Result.failure(IllegalArgumentException("Versi QR tidak didukung: v$version"))
        }
        if (host.isBlank()) {
            return Result.failure(IllegalArgumentException("Host IP tidak boleh kosong"))
        }
        if (port !in 1..65535) {
            return Result.failure(IllegalArgumentException("Port tidak valid: $port"))
        }
        if (share.isBlank()) {
            return Result.failure(IllegalArgumentException("Nama share tidak boleh kosong"))
        }
        if (user.isBlank()) {
            return Result.failure(IllegalArgumentException("Username tidak boleh kosong"))
        }
        if (pass.isBlank()) {
            return Result.failure(IllegalArgumentException("Password tidak boleh kosong"))
        }
        if (isExpired()) {
            return Result.failure(IllegalStateException("QR Code telah kedaluwarsa"))
        }
        return Result.success(Unit)
    }
}

data class WifiPayload(
    val security: String,
    val ssid: String,
    val password: String
)
