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
import androidx.annotation.RequiresApi
import com.itshare.client.core.model.WifiPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class WifiConnector(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callbackLock = Any()
    private val connectMutex = Mutex()
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    suspend fun connect(payload: WifiPayload, timeoutMs: Long = 30000): Result<Unit> = withContext(Dispatchers.IO) {
        connectMutex.withLock {
        try {
            val security = payload.security.uppercase()
            if (security !in setOf("WPA", "WEP", "NOPASS")) {
                return@withLock Result.failure(IllegalArgumentException("Jenis keamanan Wi-Fi tidak didukung: ${payload.security}"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && security == "WEP") {
                return@withLock Result.failure(IllegalArgumentException("Android 10+ tidak mendukung sambungan WEP otomatis. Sambungkan ke Wi-Fi ini secara manual."))
            }

            val connected = withTimeoutOrNull(timeoutMs.milliseconds) {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        }
    }

    fun disconnect() {
        releaseActiveRequest()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun connectAndroid10Plus(payload: WifiPayload): Boolean =
        suspendCancellableCoroutine { continuation ->
            releaseActiveRequest()
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
                    val bound = synchronized(callbackLock) {
                        activeNetworkCallback === this &&
                            continuation.isActive &&
                            runCatching { connectivityManager.bindProcessToNetwork(network) }
                                .getOrDefault(false)
                                .also { didBind -> if (didBind) boundNetwork = network }
                    }
                    if (bound && continuation.isActive) {
                        // Keep the request alive while ItShare uses this local network for SMB.
                        continuation.resume(true)
                    } else {
                        releaseRequest(this)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                override fun onUnavailable() {
                    releaseRequest(this)
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onLost(network: Network) {
                    val wasBound = synchronized(callbackLock) { boundNetwork == network }
                    if (wasBound) releaseRequest(this)
                }
            }

            synchronized(callbackLock) { activeNetworkCallback = callback }
            try {
                connectivityManager.requestNetwork(request, callback)
            } catch (_: Exception) {
                releaseRequest(callback)
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                releaseRequest(callback)
            }
        }

    private fun releaseRequest(callback: ConnectivityManager.NetworkCallback) {
        val shouldRelease = synchronized(callbackLock) {
            if (activeNetworkCallback === callback) {
                activeNetworkCallback = null
                boundNetwork = null
                true
            } else false
        }
        if (shouldRelease) {
            runCatching { connectivityManager.bindProcessToNetwork(null) }
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    private fun releaseActiveRequest() {
        val callback = synchronized(callbackLock) {
            val current = activeNetworkCallback
            activeNetworkCallback = null
            boundNetwork = null
            current
        } ?: return
        runCatching { connectivityManager.bindProcessToNetwork(null) }
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
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
