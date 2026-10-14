package com.itshare.client.core.qr

import org.junit.Assert.*
import org.junit.Test

class QrParserTest {

    @Test
    fun parseSmbPayload_validPermanentSession_returnsSuccess() {
        val json = """{"v":1,"host":"192.168.1.50","port":4455,"share":"SHARE","user":"u123","pass":"secret1234567890","ro":false}"""
        val result = QrParser.parseSmbPayload(json)

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertEquals(1, payload.version)
        assertEquals("192.168.1.50", payload.host)
        assertEquals(4455, payload.port)
        assertEquals("SHARE", payload.share)
        assertEquals("u123", payload.user)
        assertEquals("secret1234567890", payload.pass)
        assertFalse(payload.readOnly)
        assertNull(payload.expEpoch)
        assertFalse(payload.isExpired())
    }

    @Test
    fun parseSmbPayload_validReadOnlyAndExp_returnsSuccess() {
        val futureEpoch = (System.currentTimeMillis() / 1000) + 3600
        val json = """{"v":1,"host":"192.168.1.20","port":445,"share":"DOCS","user":"userX","pass":"passY","ro":true,"exp":$futureEpoch}"""
        val result = QrParser.parseSmbPayload(json)

        assertTrue(result.isSuccess)
        val payload = result.getOrThrow()
        assertTrue(payload.readOnly)
        assertEquals(futureEpoch, payload.expEpoch)
        assertFalse(payload.isExpired())
    }

    @Test
    fun parseSmbPayload_expiredSession_returnsFailure() {
        val pastEpoch = (System.currentTimeMillis() / 1000) - 600
        val json = """{"v":1,"host":"192.168.1.20","port":445,"share":"DOCS","user":"userX","pass":"passY","ro":false,"exp":$pastEpoch}"""
        val result = QrParser.parseSmbPayload(json)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun parseSmbPayload_invalidVersion_returnsFailure() {
        val json = """{"v":2,"host":"192.168.1.20","port":445,"share":"DOCS","user":"userX","pass":"passY","ro":false}"""
        val result = QrParser.parseSmbPayload(json)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Versi") == true)
    }

    @Test
    fun parseWifiPayload_standardWpa_returnsSuccess() {
        val raw = "WIFI:T:WPA;S:MyHomeWiFi;P:SecretPassword123;;"
        val result = QrParser.parseWifiPayload(raw)

        assertTrue(result.isSuccess)
        val wifi = result.getOrThrow()
        assertEquals("WPA", wifi.security)
        assertEquals("MyHomeWiFi", wifi.ssid)
        assertEquals("SecretPassword123", wifi.password)
    }

    @Test
    fun parseWifiPayload_withEscapedSpecialCharacters_unescapesProperly() {

        val raw = """WIFI:T:WPA;S:Home\;Network\,5G\:Test\\Done;P:p@ss\;word\,123\:abc\\xyz;;"""
        val result = QrParser.parseWifiPayload(raw)

        assertTrue(result.isSuccess)
        val wifi = result.getOrThrow()
        assertEquals("Home;Network,5G:Test\\Done", wifi.ssid)
        assertEquals("p@ss;word,123:abc\\xyz", wifi.password)
    }

    @Test
    fun parseWifiPayload_openNetwork_returnsSuccess() {
        val raw = "WIFI:T:nopass;S:FreePublicWiFi;P:;;"
        val result = QrParser.parseWifiPayload(raw)

        assertTrue(result.isSuccess)
        val wifi = result.getOrThrow()
        assertEquals("nopass", wifi.security)
        assertEquals("FreePublicWiFi", wifi.ssid)
        assertEquals("", wifi.password)
    }
}
