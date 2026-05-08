package com.aerohand.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WifiScanService(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    @SuppressLint("MissingPermission")
    suspend fun scan2g(): List<WifiNetworkItem> {
        if (!wifiManager.isWifiEnabled) {
            return emptyList()
        }

        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { continuation ->
                var registered = false
                lateinit var receiver: BroadcastReceiver

                fun cleanup() {
                    if (registered) {
                        runCatching { appContext.unregisterReceiver(receiver) }
                        registered = false
                    }
                }

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        cleanup()
                        if (continuation.isActive) {
                            continuation.resume(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                        }
                    }
                }

                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                registered = true
                continuation.invokeOnCancellation { cleanup() }

                if (!wifiManager.startScan()) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        }

        return wifiManager.scanResults
            .asSequence()
            .filter { it.SSID.isNotBlank() }
            .filter { it.frequency in 2400..2500 }
            .groupBy { it.SSID }
            .map { (ssid, results) ->
                val best = results.maxBy { it.level }
                WifiNetworkItem(ssid, best.level, best.frequency)
            }
            .sortedByDescending { it.level }
            .take(20)
            .toList()
    }
}

data class WifiNetworkItem(
    val ssid: String,
    val level: Int,
    val frequency: Int
)
