package com.example.proxyvpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

// التطبيقات اللي عندها أيقونة تشغيل فقط (تطبيقات المستخدم عادةً)
fun loadLaunchableApps(pm: PackageManager, selfPkg: String): List<AppEntry> =
    pm.getInstalledApplications(0)
        .filter { it.packageName != selfPkg }
        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }

@Composable
fun AppsScreen(
    selected: Set<String>,
    onToggle: (pkg: String, on: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx.packageManager, ctx.packageName) }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text("استثناء التطبيقات", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "التطبيقات المفعّلة تتصل مباشرة بالإنترنت الأصلي بدون بروكسي. تُطبَّق عند الاتصال التالي.",
            style = MaterialTheme.typography.bodySmall
        )
        Text("المفعّل: ${selected.size}", style = MaterialTheme.typography.labelLarge)

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(apps, key = { it.pkg }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = app.pkg in selected,
                            onCheckedChange = { onToggle(app.pkg, it) }
                        )
                    }
                }
            }
        }
    }
}
