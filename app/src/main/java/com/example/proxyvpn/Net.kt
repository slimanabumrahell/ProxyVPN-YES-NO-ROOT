package com.example.proxyvpn

import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun publicIp(): String? = try {
        val c = URL("https://api.ipify.org").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        null
    }
}
