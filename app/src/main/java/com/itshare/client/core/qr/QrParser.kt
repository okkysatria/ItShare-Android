package com.itshare.client.core.qr

import com.itshare.client.core.model.SmbPayload
import com.itshare.client.core.model.WifiPayload
import kotlinx.serialization.json.Json

object QrParser {

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parseSmbPayload(rawText: String): Result<SmbPayload> {
        return runCatching {
            val payload = json.decodeFromString<SmbPayload>(rawText.trim())
            payload.validate().getOrThrow()
            payload
        }
    }

    fun parseWifiPayload(rawText: String): Result<WifiPayload> {
        return runCatching {
            val trimmed = rawText.trim()
            if (!trimmed.startsWith("WIFI:", ignoreCase = true)) {
                throw IllegalArgumentException("Format QR WiFi tidak valid: tidak diawali dengan WIFI:")
            }

            val body = trimmed.substring(5)
            val fields = parseEscapedSemicolonFields(body)

            val security = fields["T"] ?: "nopass"
            val ssid = fields["S"] ?: throw IllegalArgumentException("SSID tidak ditemukan dalam QR WiFi")
            val password = fields["P"] ?: ""

            WifiPayload(
                security = security,
                ssid = ssid,
                password = password
            )
        }
    }

    private fun parseEscapedSemicolonFields(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val currentToken = StringBuilder()
        var isEscaped = false

        val tokens = mutableListOf<String>()

        for (ch in content) {
            if (isEscaped) {
                currentToken.append(ch)
                isEscaped = false
            } else if (ch == '\\') {
                isEscaped = true
            } else if (ch == ';') {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken.clear()
                }
            } else {
                currentToken.append(ch)
            }
        }
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }

        for (token in tokens) {
            val colonIndex = token.indexOf(':')
            if (colonIndex > 0) {
                val key = token.substring(0, colonIndex).trim()
                val rawValue = token.substring(colonIndex + 1)
                result[key] = rawValue
            }
        }

        return result
    }
}
