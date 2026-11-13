package com.example.proxyvpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
    val icon: Drawable?
)

fun loadApps(pm: PackageManager, selfPkg: String): List<AppEntry> =
    pm.getInstalledApplications(0)
        .filter { it.packageName != selfPkg }
        .map {
            AppEntry(
                pkg = it.packageName,
                label = pm.getApplicationLabel(it).toString(),
                isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                icon = try { pm.getApplicationIcon(it) } catch (_: Exception) { null }
            )
        }
        .sortedBy { it.label.lowercase() }

fun Drawable.toBitmapSafe(size: Int = 96): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    selected: Set<String>,
    onToggle: (pkg: String, on: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx.packageManager, ctx.packageName) }
        loading = false
    }

    val visible = apps
        .filter { showSystem || !it.isSystem }
        .filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true) ||
                it.pkg.contains(query, ignoreCase = true)
        }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text(
                "استثناء التطبيقات",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "خيارات",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("إظهار تطبيقات النظام") },
                        leadingIcon = {
                            Checkbox(checked = showSystem, onCheckedChange = null)
                        },
                        onClick = { showSystem = !showSystem }
                    )
                }
            }
        }

        Text(
            "التطبيقات المفعّلة تتصل مباشرة بالإنترنت الأصلي بدون بروكسي. تُطبَّق عند الاتصال التالي.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("ابحث بالاسم أو الحزمة") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "المفعّل: ${selected.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(visible, key = { it.pkg }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        app.icon?.let { d ->
                            val bmp = remember(app.pkg) { d.toBitmapSafe() }
                            Image(
                                painter = BitmapPainter(bmp.asImageBitmap()),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
