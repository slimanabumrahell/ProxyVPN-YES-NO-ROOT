package com.example.proxyvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

class ProxyVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var hevPfd: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var gostProc: Process? = null

    @Volatile private var running = false
    @Volatile private var tunnelUp = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        val c = ProxyStore(this).load()
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال عبر ${c.host}:${c.port}"))

        val builder = Builder()
            .setSession("ProxyVPN")
            .setMtu(8500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(c.dns)

        builder.addDisallowedApplication(packageName)
        c.excluded.forEach { pkg ->
            try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
        }

        tun = builder.establish()
        if (tun == null) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        state.value = VpnState(active = true, tunnelUp = false, localVia = false)
        startWatchdog(c)
        return START_STICKY
    }

    // ---------- الكشف التلقائي للنوع ----------

    private fun resolveType(c: ProxyConfig): ProxyConfig {
        if (!c.autoDetect) return c
        val detected = RootTunnel.detectType(c.host, c.port, c.user, c.pass) ?: return c
        return c.copy(type = detected)
    }

    // ---------- gost (محوّل محلي بدون root) ----------

    private fun gostArgs(c: ProxyConfig): String {
        val scheme = when (c.type) {
            "socks4" -> "socks4"
            "http" -> "http"
            "https" -> "https"
            else -> "socks5"
        }
        val auth = if (c.user.isNotEmpty())
            "${URLEncoder.encode(c.user, "UTF-8")}:${URLEncoder.encode(c.pass, "UTF-8")}@" else ""
        return "-L socks5://127.0.0.1:$LOCAL_PORT -F $scheme://$auth${c.host}:${c.port}"
    }

    private fun startGost(c: ProxyConfig) {
        stopGost()
        val bin = File(applicationInfo.nativeLibraryDir, "libgost.so").absolutePath
        val args = gostArgs(c).split(" ")
        gostProc = ProcessBuilder(listOf(bin) + args)
            .redirectErrorStream(true)
            .redirectOutput(File(cacheDir, "gost.log"))
            .start()
    }

    private fun stopGost() {
        try { gostProc?.destroy() } catch (_: Throwable) {}
        gostProc = null
    }

    // ---------- المراقب والنفق ----------

    private fun startWatchdog(c: ProxyConfig) {
        worker = Thread {
            while (running) {
                val reachable = proxyReachable(c)
                if (!running) break
                if (reachable && !tunnelUp) {
                    startTunnelNow(c)
                } else if (!reachable && tunnelUp) {
                    stopTunnelNow()
                    updateNotification("الخادم مقطوع، الحركة محجوبة (Kill switch)")
                } else if (!reachable && !tunnelUp) {
                    updateNotification("بانتظار الخادم ${c.host}:${c.port}")
                }
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
            }
        }.apply {
            name = "proxy-watchdog"
            isDaemon = true
            start()
        }
    }

    private fun startTunnelNow(c: ProxyConfig) {
        if (!running) return
        val t = tun ?: return

        val cc = resolveType(c)

        // أنواع غير SOCKS5 تمر عبر gost المحلي، وSOCKS5 يتصل مباشرة
        val viaLocal = cc.type != "socks5"
        val local = if (!viaLocal) cc else {
            startGost(cc)
            Thread.sleep(1500)
            cc.copy(host = "127.0.0.1", port = LOCAL_PORT, user = "", pass = "")
        }

        val dupPfd = t.dup()
        hevPfd = dupPfd
        TunnelBridge.start(
            configDir = cacheDir,
            tunFd = dupPfd.fd,
            host = local.host,
            port = local.port,
            user = local.user,
            pass = local.pass
        )
        tunnelUp = true
        state.update { it.copy(tunnelUp = true, localVia = viaLocal) }
        updateNotification("متصل عبر ${cc.host}:${cc.port} (${cc.type.uppercase()})")
    }

    private fun stopTunnelNow() {
        try { TunnelBridge.stop() } catch (_: Throwable) {}
        tunnelUp = false
        state.update { it.copy(tunnelUp = false, localVia = false) }
        try { hevPfd?.close() } catch (_: Exception) {}
        hevPfd = null
        stopGost()
    }

    private fun shutdown() {
        running = false
        worker?.interrupt()
        worker = null
        stopTunnelNow()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        state.value = VpnState()
    }

    private fun proxyReachable(c: ProxyConfig): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(c.host, c.port), 5000)
            true
        }
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val chId = "vpn"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(chId, "ProxyVPN", NotificationManager.IMPORTANCE_LOW)
            )
        return Notification.Builder(this, chId)
            .setContentTitle("ProxyVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    data class VpnState(
        val active: Boolean = false,
        val tunnelUp: Boolean = false,
        val localVia: Boolean = false // true إذا التوجيه عبر gost المحلي
    )

    companion object {
        const val ACTION_STOP = "com.example.proxyvpn.STOP"
        const val NOTIF_ID = 1
        const val LOCAL_PORT = 1081
        val state = MutableStateFlow(VpnState())
    }
}
