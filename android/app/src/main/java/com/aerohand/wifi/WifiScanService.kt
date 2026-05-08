package com.aerohand.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager

class WifiScanService(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @SuppressLint("MissingPermission")
    fun scan2g(): List<WifiNetworkItem> {
        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }
        wifiManager.startScan()
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
