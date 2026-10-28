package com.itshare.client.core.security

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.itshare.client.core.model.SmbPayload
import kotlinx.serialization.json.Json

class SecureSessionStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "itshare_secure_sessions",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun savePermanentSession(
        payload: SmbPayload,
        lastConnectedEpoch: Long = System.currentTimeMillis() / 1000
    ) {
        if (payload.expEpoch != null && payload.expEpoch > 0) return
        val key = "${payload.host}:${payload.port}:${payload.share}"
        val serialized = json.encodeToString(SmbPayload.serializer(), payload)
        sharedPreferences.edit {
            putString(key, serialized)
            putLong(lastConnectedKey(key), lastConnectedEpoch.coerceAtLeast(0L))
        }
    }

    fun getAllSavedSessions(): List<SmbPayload> {
        val list = mutableListOf<SmbPayload>()
        for ((key, value) in sharedPreferences.all) {
            if (!key.startsWith(LAST_CONNECTED_PREFIX) && value is String) {
                runCatching {
                    val payload = json.decodeFromString<SmbPayload>(value)
                    val sessionKey = "${payload.host}:${payload.port}:${payload.share}"
                    list.add(payload.copy(lastConnectedEpoch = sharedPreferences.getLong(lastConnectedKey(sessionKey), 0L)))
                }
            }
        }
        return list
    }

    fun removeSession(payload: SmbPayload) {
        val key = "${payload.host}:${payload.port}:${payload.share}"
        sharedPreferences.edit {
            remove(key)
            remove(lastConnectedKey(key))
        }
    }

    private fun lastConnectedKey(sessionKey: String): String = "$LAST_CONNECTED_PREFIX$sessionKey"

    private companion object {
        const val LAST_CONNECTED_PREFIX = "last_connected:"
    }
}
