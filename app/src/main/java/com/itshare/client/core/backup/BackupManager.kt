package com.itshare.client.core.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SessionHistoryItem(
    val host: String,
    val port: Int,
    val share: String,
    val readOnly: Boolean,
    val lastConnectedEpoch: Long
)

@Serializable
data class AppBackupData(
    @SerialName("v") val version: Int = 1,
    @SerialName("created_at") val createdAtEpoch: Long = System.currentTimeMillis() / 1000,
    @SerialName("history") val history: List<SessionHistoryItem> = emptyList(),
    @SerialName("settings") val settings: Map<String, String> = emptyMap()
)

object BackupManager {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun exportBackupJson(history: List<SessionHistoryItem>, settings: Map<String, String>): String {
        val backupData = AppBackupData(
            version = 1,
            createdAtEpoch = System.currentTimeMillis() / 1000,
            history = history,
            settings = settings
        )
        return json.encodeToString(AppBackupData.serializer(), backupData)
    }

    fun importBackupJson(
        jsonString: String,
        existingHistory: List<SessionHistoryItem>
    ): Result<List<SessionHistoryItem>> {
        return runCatching {
            val backup = json.decodeFromString(AppBackupData.serializer(), jsonString)
            if (backup.version != 1) {
                throw IllegalArgumentException("Versi backup tidak didukung: v${backup.version}")
            }

            val combined = (existingHistory + backup.history).distinctBy { "${it.host}:${it.port}:${it.share}" }
            combined
        }
    }
}
