package com.itshare.client.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.itshare.client.core.model.SmbPayload
import kotlinx.serialization.encodeToString
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

    fun savePermanentSession(payload: SmbPayload) {
        if (payload.expEpoch != null && payload.expEpoch > 0) return
        val key = "${payload.host}:${payload.port}:${payload.share}"
        val serialized = json.encodeToString(payload)
        sharedPreferences.edit().putString(key, serialized).apply()
    }

    fun getAllSavedSessions(): List<SmbPayload> {
        val list = mutableListOf<SmbPayload>()
        for ((_, value) in sharedPreferences.all) {
            if (value is String) {
                runCatching {
                    list.add(json.decodeFromString<SmbPayload>(value))
                }
            }
        }
        return list
    }

    fun removeSession(payload: SmbPayload) {
        val key = "${payload.host}:${payload.port}:${payload.share}"
        sharedPreferences.edit().remove(key).apply()
    }

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }
}
