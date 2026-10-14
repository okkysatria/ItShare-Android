package com.itshare.client.core.backup

import org.junit.Assert.*
import org.junit.Test

class BackupManagerTest {

    @Test
    fun exportBackupJson_sanitizesPasswordsAndOutputsValidJson() {
        val history = listOf(
            SessionHistoryItem(
                host = "192.168.1.10",
                port = 445,
                share = "Media",
                readOnly = false,
                lastConnectedEpoch = 1700000000L
            ),
            SessionHistoryItem(
                host = "192.168.1.20",
                port = 4455,
                share = "BackupDocs",
                readOnly = true,
                lastConnectedEpoch = 1700001000L
            )
        )

        val settings = mapOf("client" to "Android", "theme" to "dark")
        val json = BackupManager.exportBackupJson(history, settings)

        assertTrue(json.contains("\"v\": 1"))
        assertTrue(json.contains("\"Media\""))
        assertTrue(json.contains("\"BackupDocs\""))

        assertFalse(json.contains("\"pass\""))
        assertFalse(json.contains("\"password\""))
    }

    @Test
    fun importBackupJson_validJson_mergesAndDeduplicates() {
        val existing = listOf(
            SessionHistoryItem(
                host = "192.168.1.10",
                port = 445,
                share = "Media",
                readOnly = false,
                lastConnectedEpoch = 1700000000L
            )
        )

        val incomingJson = """
        {
          "v": 1,
          "created_at": 1700005000,
          "history": [
            {
              "host": "192.168.1.10",
              "port": 445,
              "share": "Media",
              "readOnly": false,
              "lastConnectedEpoch": 1700002000
            },
            {
              "host": "192.168.1.50",
              "port": 445,
              "share": "SharedFiles",
              "readOnly": true,
              "lastConnectedEpoch": 1700003000
            }
          ],
          "settings": {}
        }
        """.trimIndent()

        val result = BackupManager.importBackupJson(incomingJson, existing)
        assertTrue(result.isSuccess)

        val combined = result.getOrThrow()
        assertEquals(2, combined.size)
        assertEquals("Media", combined[0].share)
        assertEquals("SharedFiles", combined[1].share)
    }

    @Test
    fun importBackupJson_unsupportedVersion_fails() {
        val incomingJson = """
        {
          "v": 99,
          "created_at": 1700005000,
          "history": [],
          "settings": {}
        }
        """.trimIndent()

        val result = BackupManager.importBackupJson(incomingJson, emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Versi") == true)
    }
}
