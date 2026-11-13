package com.example.proxyvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetSocketAddress
import java.net.Socket

class ProxyVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var hevPfd: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var cfg: ProxyConfig? = null

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
        cfg = c
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال عبر ${c.host}:${c.port}"))

        val builder = Builder()
            .setSession("ProxyVPN")
            .setMtu(8500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(c.dns)

        // التطبيق نفسه يتجاوز النفق عشان سوكيت البروكسي ما يدخل فيه
        builder.addDisallowedApplication(packageName)

        // التطبيقات المستثناة من المستخدم (لو مش مثبتة نتجاهلها)
        c.excluded.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: Exception) {
                // التطبيق مش موجود حالياً
            }
        }

        tun = builder.establish()

        if (tun == null) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        state.value = VpnState(active = true, tunnelUp = false)
        startWatchdog(c)
        return START_STICKY
    }

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
        // نعطي hev نسخة مستقلة من الـ fd، ونحتفظ بها عشان نغلقها بأنفسنا
        val dupPfd = t.dup()
        hevPfd = dupPfd
        TunnelBridge.start(
            configDir = cacheDir,
            tunFd = dupPfd.fd,
            host = c.host,
            port = c.port,
            user = c.user,
            pass = c.pass
        )
        tunnelUp = true
        state.update { it.copy(tunnelUp = true) }
        updateNotification("متصل عبر ${c.host}:${c.port}")
    }

    private fun stopTunnelNow() {
        try { TunnelBridge.stop() } catch (_: Throwable) {}
        tunnelUp = false
        state.update { it.copy(tunnelUp = false) }
        // نغلق نسخة hev بعد إيقاف المكتبة
        try { hevPfd?.close() } catch (_: Exception) {}
        hevPfd = null
    }

    private fun shutdown() {
        // أولاً أوقف الـ watchdog حتى ما يعيد تشغيل النفق
        running = false
        worker?.interrupt()
        worker = null
        stopTunnelNow()
        // إغلاق الواجهة الأصلية نهائياً
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

    data class VpnState(val active: Boolean = false, val tunnelUp: Boolean = false)

    companion object {
        const val ACTION_STOP = "com.example.proxyvpn.STOP"
        const val NOTIF_ID = 1
        val state = MutableStateFlow(VpnState())
    }
}
