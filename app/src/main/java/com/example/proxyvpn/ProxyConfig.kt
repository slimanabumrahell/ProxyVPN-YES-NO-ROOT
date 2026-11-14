package com.example.proxyvpn

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class ProxyConfig(
    val host: String = "",
    val port: Int = 1080,
    val user: String = "",
    val pass: String = "",
    val dns: String = "1.1.1.1",
    val excluded: Set<String> = emptySet(),
    val mode: String = "vpn", // "vpn" أو "root"
    val type: String = "socks5", // socks5 | socks4 | http | https
    val autoDetect: Boolean = false // كشف تلقائي للنوع (SOCKS5 / SOCKS4 / HTTP)
)

class ProxyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "proxy_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load() = ProxyConfig(
        host = prefs.getString("host", "") ?: "",
        port = prefs.getInt("port", 1080),
        user = prefs.getString("user", "") ?: "",
        pass = prefs.getString("pass", "") ?: "",
        dns = prefs.getString("dns", "1.1.1.1") ?: "1.1.1.1",
        excluded = prefs.getStringSet("excluded", emptySet())?.toSet() ?: emptySet(),
        mode = prefs.getString("mode", "vpn") ?: "vpn",
        type = prefs.getString("type", "socks5") ?: "socks5",
        autoDetect = prefs.getBoolean("autoDetect", false)
    )

    fun save(c: ProxyConfig) {
        prefs.edit()
            .putString("host", c.host)
            .putInt("port", c.port)
            .putString("user", c.user)
            .putString("pass", c.pass)
            .putString("dns", c.dns)
            .putStringSet("excluded", c.excluded)
            .putString("mode", c.mode)
            .putString("type", c.type)
            .putBoolean("autoDetect", c.autoDetect)
            .apply()
    }
}
