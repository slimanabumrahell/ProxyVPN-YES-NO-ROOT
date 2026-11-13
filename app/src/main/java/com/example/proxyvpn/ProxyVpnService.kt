package com.example.proxyvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.net.InetSocketAddress
import java.net.Socket

class ProxyVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var cfg: ProxyConfig? = null

    @Volatile private var running = false
    @Volatile private var tunnelUp = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        val c = ProxyStore(this).load()
        cfg = c
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال عبر ${c.host}:${c.port}"))

        tun = Builder()
            .setSession("ProxyVPN")
            .setMtu(8500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(c.dns)
            .addDisallowedApplication(packageName)
            .establish()

        if (tun == null) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        startWatchdog(c)
        return START_STICKY
    }

    private fun startWatchdog(c: ProxyConfig) {
        worker = Thread {
            while (running) {
                val reachable = proxyReachable(c)
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
        val t = tun ?: return
        // نعطي hev نسخة مستقلة من الـ fd، فإغلاقها ما يسكّر الواجهة الأصلية
        val rawFd = t.dup().detachFd()
        TunnelBridge.start(
            configDir = cacheDir,
            tunFd = rawFd,
            host = c.host,
            port = c.port,
            user = c.user,
            pass = c.pass
        )
        tunnelUp = true
        updateNotification("متصل عبر ${c.host}:${c.port}")
    }

    private fun stopTunnelNow() {
        TunnelBridge.stop()
        tunnelUp = false
    }

    private fun shutdown() {
        running = false
        worker?.interrupt()
        worker = null
        stopTunnelNow()
        tun?.close()
        tun = null
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

    companion object {
        const val ACTION_STOP = "com.example.proxyvpn.STOP"
        const val NOTIF_ID = 1
    }
}
