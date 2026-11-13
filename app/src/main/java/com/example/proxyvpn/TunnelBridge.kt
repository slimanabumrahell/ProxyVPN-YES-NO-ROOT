package com.example.proxyvpn

import hev.htproxy.TProxyService
import java.io.File

object TunnelBridge {

    fun start(configDir: File, tunFd: Int, host: String, port: Int, user: String, pass: String) {
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
        TProxyService.TProxyStartService(conf.absolutePath, tunFd)
    }

    fun stop() {
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
    }
}
