package com.example.proxyvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class ProxyVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        val cfg = ProxyStore(this).load()
        startForeground(NOTIF_ID, buildNotification("متصل عبر ${cfg.host}:${cfg.port}"))
        startTunnel(cfg)
        return START_STICKY
    }

    private fun startTunnel(cfg: ProxyConfig) {
        stopTunnel()
        tun = Builder()
            .setSession("ProxyVPN")
            .setMtu(8500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(cfg.dns)
            .addDisallowedApplication(packageName) // عشان سوكيت البروكسي ما يدخل النفق
            .establish()
            ?: return

        TunnelBridge.start(
            configDir = cacheDir,
            tunFd = tun!!.fd,
            host = cfg.host,
            port = cfg.port,
            user = cfg.user,
            pass = cfg.pass
        )
    }

    private fun stopTunnel() {
        TunnelBridge.stop()
        tun?.close()
        tun = null
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
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
