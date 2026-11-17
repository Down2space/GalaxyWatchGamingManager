package com.gwgm.manager.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gwgm.manager.adb.ConnState
import com.gwgm.manager.safety.AppCategory
import com.gwgm.manager.ui.theme.Amber

@Composable
fun GamesScreen(ui: UiState, vm: MainViewModel) {
    val connected = ui.conn is ConnState.Connected
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onApkPicked(uri)
    }
    val userApps = ui.apps.filter { it.classification.category == AppCategory.USER && it.installedForUser && it.isEnabled }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GAMES", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
        if (!connected) Text("Connect to the watch on the Watch tab first.", color = Amber, fontSize = 13.sp)

        SectionCard("Install a game on the watch") {
            Text(
                "Pick a game's .apk file from your phone (e.g. Downloads). The app checks it first — " +
                    "watch compatibility, processor type, Android version and free space — then shows you a review before installing.",
                fontSize = 13.sp,
            )
            Button(
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                enabled = connected && !ui.planning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose APK file…") }
            if (ui.planning) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text("Checking the APK and the watch…", fontSize = 13.sp)
            }
            ui.pickedApk?.let { a ->
                InfoRow("Last checked", a.label)
                InfoRow("Package", a.packageName)
                InfoRow("Made for watches", if (a.isWatchApp) "Yes" else "No — may not work well")
                InfoRow("Processor code", a.abis.ifEmpty { listOf("any (no native code)") }.joinToString())
            }
            Text(
                "Works with single .apk files. Bundles (.apks, .xapk, .aab) aren't supported yet. " +
                    "Regular phone games usually don't work well on a round watch screen; Wear OS games are the safe bet. " +
                    "HTML/web games need the watch companion app (coming in Phase 5).",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Installed apps you can open") {
            if (ui.apps.isEmpty()) {
                Text("Load the list to see games you've installed.", fontSize = 13.sp)
                OutlinedButton(onClick = vm::loadInventory, enabled = connected && !ui.appsLoading) {
                    Text(if (ui.appsLoading) "Loading…" else "Load installed apps")
                }
            } else {
                if (userApps.isEmpty()) Text("No user-installed apps yet.", fontSize = 13.sp)
                userApps.forEach { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.displayName, fontSize = 14.sp)
                            Text(app.packageName, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { vm.launchOnWatch(app) }, enabled = connected && ui.launching == null) {
                            Text(if (ui.launching == app.packageName) "Opening…" else "Open on watch")
                        }
                    }
                }
                OutlinedButton(onClick = vm::loadInventory, enabled = connected && !ui.appsLoading) { Text("Reload") }
            }
        }

        SectionCard("Watch resources") {
            val i = ui.info
            InfoRow("RAM available", i?.memory?.let { "${formatKb(it.availableKb)} of ${formatKb(it.totalKb)}" })
            InfoRow("Storage free", i?.storage?.let { "${formatKb(it.availableKb)} of ${formatKb(it.totalKb)}" })
            InfoRow("Battery", i?.battery?.levelPercent?.let { "$it%" })
            OutlinedButton(onClick = vm::refresh, enabled = connected && !ui.infoLoading) { Text("Refresh") }
        }
    }
}
