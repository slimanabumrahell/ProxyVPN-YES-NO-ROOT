package com.example.proxyvpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) startVpnService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Screen() }
        }
    }

    private fun startVpnService() {
        startForegroundService(Intent(this, ProxyVpnService::class.java))
    }

    private fun connect() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpnService()
    }

    private fun disconnect() {
        // أرسل أمر الإيقاف للخدمة، وبعدها أوقفها مباشرة كخطة احتياطية
        startService(
            Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP)
        )
        stopService(Intent(this, ProxyVpnService::class.java))
    }

    @Composable
    private fun Screen() {
        val store = remember { ProxyStore(this) }
        var cfg by remember { mutableStateOf(store.load()) }
        var connected by remember { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }
        var realIp by remember { mutableStateOf("...") }
        var proxyIp by remember { mutableStateOf("-") }
        val scope = rememberCoroutineScope()

        if (showApps) {
            AppsScreen(
                selected = cfg.excluded,
                onToggle = { pkg, on ->
                    val newSet = if (on) cfg.excluded + pkg else cfg.excluded - pkg
                    cfg = cfg.copy(excluded = newSet)
                    store.save(cfg)
                },
                onBack = { showApps = false }
            )
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ProxyVPN", style = MaterialTheme.typography.headlineMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (connected) "متصل" else "غير متصل",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("IP الحقيقي: $realIp")
                    Text("IP البروكسي: $proxyIp")
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (connected) {
                                disconnect()
                                connected = false
                                proxyIp = "-"
                            } else {
                                store.save(cfg)
                                connect()
                                connected = true
                                scope.launch {
                                    proxyIp = withContext(Dispatchers.IO) {
                                        Net.ipViaSocks(cfg.host, cfg.port, cfg.user, cfg.pass) ?: "فشل"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (connected) "قطع الاتصال" else "اتصال") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("إعدادات SOCKS5", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(cfg.host, { cfg = cfg.copy(host = it) },
                        label = { Text("IP / Host") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cfg.port.toString(),
                        { cfg = cfg.copy(port = it.toIntOrNull() ?: 0) },
                        label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cfg.user, { cfg = cfg.copy(user = it) },
                        label = { Text("Username (اختياري)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cfg.pass, { cfg = cfg.copy(pass = it) },
                        label = { Text("Password (اختياري)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cfg.dns, { cfg = cfg.copy(dns = it) },
                        label = { Text("DNS") }, modifier = Modifier.fillMaxWidth())
                }
            }

            OutlinedButton(
                onClick = { showApps = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("استثناء التطبيقات (${cfg.excluded.size})") }

            Button(
                onClick = {
                    scope.launch {
                        realIp = withContext(Dispatchers.IO) { Net.publicIp() } ?: "فشل"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("قراءة IP الحقيقي") }
        }
    }
}
