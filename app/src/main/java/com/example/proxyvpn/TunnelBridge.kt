package com.example.proxyvpn

import java.io.File

/**
 * يربط التطبيق بمكتبة hev-socks5-tunnel.
 * الدوال هنا تستدعي TProxyService من حزمة hev.htproxy
 * (الملف TProxyService.java لازم يكون موجود في المشروع، انظر ملاحظة الإعداد).
 */
object TunnelBridge {

    fun start(configDir: java.io.File, tunFd: Int, host: String, port: Int, user: String, pass: String) {
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("socks5:")
            appendLine("  address: $host")
            appendLine("  port: $port")
            if (user.isNotEmpty()) {
                appendLine("  username: '${user.replace("'", "''")}'")
                appendLine("  password: '${pass.replace("'", "''")}'")
            }
            appendLine("  udp: 'udp'")
        }
        val conf = File(configDir, "tunnel.yml").apply { writeText(yaml) }
        hev.htproxy.TProxyService.TProxyStartService(conf.absolutePath, tunFd)
    }

    fun stop() {
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Throwable) {}
    }
}
