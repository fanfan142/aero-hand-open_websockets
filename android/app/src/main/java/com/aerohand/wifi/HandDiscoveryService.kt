package com.aerohand.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Only live, resolved services are selectable. No resolved IP is persisted across scans. */
data class DiscoveredHand(val name: String, val host: String, val port: Int)

class HandDiscoveryService(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<DiscoveredHand>>(emptyList())
    val devices: StateFlow<List<DiscoveredHand>> = _devices
    private val _status = MutableStateFlow("展开 WiFi 面板后搜索局域网设备")
    val status: StateFlow<String> = _status

    private var listener: NsdManager.DiscoveryListener? = null
    private val found = mutableMapOf<String, NsdServiceInfo>()
    private val resolved = mutableMapOf<String, DiscoveredHand>()
    private val pending = ArrayDeque<String>()
    private var resolving = false

    // All mutable discovery state is confined to the main thread.
    fun start() {
        if (listener != null) return
        val manager = nsdManager ?: run {
            _status.value = "系统不支持设备搜索，请手动输入 IP"
            return
        }
        found.clear()
        resolved.clear()
        pending.clear()
        resolving = false
        _devices.value = emptyList()
        _status.value = "正在搜索局域网设备…"
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                mainHandler.post { if (listener === this) _status.value = "正在搜索局域网设备…" }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                mainHandler.post {
                    if (listener !== this) return@post
                    val name = serviceInfo.serviceName
                    if (name.isBlank()) return@post
                    found[name] = serviceInfo
                    if (name !in resolved && name !in pending) pending.addLast(name)
                    resolveNext(this)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                mainHandler.post {
                    if (listener !== this) return@post
                    val name = serviceInfo.serviceName
                    found.remove(name)
                    resolved.remove(name)
                    pending.remove(name)
                    publishDevices()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                mainHandler.post {
                    if (listener === this) {
                        stop()
                        _status.value = "设备搜索不可用 ($errorCode)，请手动输入 IP"
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // No stale entries may be selected even if Android cannot stop the listener.
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        listener = discovery
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: SecurityException) {
            stop()
            _status.value = "无局域网搜索权限，请手动输入 IP"
        } catch (_: RuntimeException) {
            stop()
            _status.value = "设备搜索不可用，请手动输入 IP"
        }
    }

    fun stop() {
        val previous = listener
        listener = null
        found.clear()
        resolved.clear()
        pending.clear()
        resolving = false
        _devices.value = emptyList()
        _status.value = "展开 WiFi 面板后搜索局域网设备"
        if (previous != null) runCatching { nsdManager?.stopServiceDiscovery(previous) }
    }

    fun currentDevice(name: String): DiscoveredHand? = resolved[name]?.takeIf { listener != null && name in found }

    private fun resolveNext(discovery: NsdManager.DiscoveryListener) {
        if (listener !== discovery || resolving) return
        val name = pending.removeFirstOrNull() ?: return
        val service = found[name] ?: run { resolveNext(discovery); return }
        val manager = nsdManager ?: return
        resolving = true
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                mainHandler.post { finish() }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                mainHandler.post {
                    if (listener === discovery && name in found) {
                        val address = serviceInfo.host as? Inet4Address
                        if (address != null && serviceInfo.port in 1..65535) {
                            resolved[name] = DiscoveredHand(name, address.hostAddress ?: return@post, serviceInfo.port)
                            publishDevices()
                        }
                    }
                    finish()
                }
            }

            private fun finish() {
                if (listener !== discovery) return
                resolving = false
                resolveNext(discovery)
            }
        }
        try {
            manager.resolveService(service, resolver)
        } catch (_: RuntimeException) {
            resolving = false
            resolveNext(discovery)
        }
    }

    private fun publishDevices() {
        _devices.value = resolved.values.sortedBy { it.name }
        _status.value = if (resolved.isEmpty()) "未发现设备，可使用手动 IP / AP 连接" else "发现 ${resolved.size} 台设备；连接后确认左右手身份"
    }

    companion object {
        private const val SERVICE_TYPE = "_aerohand._tcp."
    }
}
