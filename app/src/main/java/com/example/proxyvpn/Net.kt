package com.example.proxyvpn

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

object Net {
    // IP الحقيقي (بدون بروكسي)
    fun publicIp(): String? = try {
        val c = URL("https://api.ipify.org").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        null
    }

    // IP عبر SOCKS5 (يمر عبر الخادم)
    fun ipViaSocks(host: String, port: Int, user: String, pass: String): String? = try {
        if (user.isNotEmpty()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(user, pass.toCharArray())
            })
        }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        val c = URL("https://api.ipify.org").openConnection(proxy) as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        null
    }
}
