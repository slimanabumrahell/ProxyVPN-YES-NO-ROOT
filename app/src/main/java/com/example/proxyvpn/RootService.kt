package com.example.proxyvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

class RootService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRoot()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("ProxyVPN - وضع الروت شغّال"))
        return START_NOT_STICKY
    }

    private fun stopRoot() {
        val cfg = ProxyStore(this).load()
        Thread {
            RootTunnel.stop(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val chId = "root"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(chId, "ProxyVPN Root", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RootService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, chId)
            .setContentTitle("ProxyVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "قطع الاتصال", stopIntent
                ).build()
            )
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.proxyvpn.ROOT_STOP"
        const val NOTIF_ID = 2
    }
}
