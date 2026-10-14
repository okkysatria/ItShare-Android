package com.itshare.client.core.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.itshare.client.core.model.WifiPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WifiConnector(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun connect(payload: WifiPayload, timeoutMs: Long = 30000): Result<Unit> = withContext(Dispatchers.IO) {
        val security = payload.security.uppercase()
        if (security !in setOf("WPA", "WEP", "NOPASS")) {
            return@withContext Result.failure(IllegalArgumentException("Jenis keamanan Wi-Fi tidak didukung: ${payload.security}"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && security == "WEP") {
            return@withContext Result.failure(IllegalArgumentException("Android 10+ tidak mendukung sambungan WEP otomatis. Sambungkan ke Wi-Fi ini secara manual."))
        }

        val connected = withTimeoutOrNull(timeoutMs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectAndroid10Plus(payload)
            } else {
                connectLegacy(payload)
            }
        }

        if (connected == true) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Koneksi ke WiFi '${payload.ssid}' gagal atau dibatalkan pengguna."))
        }
    }

    private suspend fun connectAndroid10Plus(payload: WifiPayload): Boolean =
        suspendCancellableCoroutine { continuation ->
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(payload.ssid)

            when (payload.security.uppercase()) {
                "WPA" -> specifierBuilder.setWpa2Passphrase(payload.password)
                "WEP" -> {

                }
                "NOPASS" -> {}
                else -> specifierBuilder.setWpa2Passphrase(payload.password)
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onUnavailable() {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onLost(network: Network) {
                    connectivityManager.bindProcessToNetwork(null)
                }
            }

            connectivityManager.requestNetwork(request, callback)

            continuation.invokeOnCancellation {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }

    @Suppress("DEPRECATION")
    private fun connectLegacy(payload: WifiPayload): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false

        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }

        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"${payload.ssid}\""
            when (payload.security.uppercase()) {
                "NOPASS" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                "WEP" -> {
                    wepKeys[0] = "\"${payload.password}\""
                    wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                }
                else -> {

                    preSharedKey = "\"${payload.password}\""
                }
            }
        }

        val netId = wifiManager.addNetwork(wifiConfig)
        if (netId == -1) return false

        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        return wifiManager.reconnect()
    }
}
