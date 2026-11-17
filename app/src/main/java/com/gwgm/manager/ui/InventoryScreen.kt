package com.gwgm.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gwgm.manager.adb.ConnState
import com.gwgm.manager.data.AppEntry
import com.gwgm.manager.safety.AppCategory
import com.gwgm.manager.ui.theme.Amber
import com.gwgm.manager.ui.theme.Cyan
import com.gwgm.manager.ui.theme.Danger
import com.gwgm.manager.ui.theme.Magenta
import com.gwgm.manager.ui.theme.Neon

fun categoryColor(c: AppCategory): Color = when (c) {
    AppCategory.PROTECTED -> Danger
    AppCategory.OPTIONAL_SAMSUNG -> Cyan
    AppCategory.USER -> Neon
    AppCategory.UNKNOWN -> Amber
}

@Composable
fun InventoryScreen(ui: UiState, vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<AppCategory?>(null) }
    val connected = ui.conn is ConnState.Connected

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("APP INVENTORY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Button(onClick = vm::loadInventory, enabled = connected && !ui.appsLoading) {
                Text(if (ui.apps.isEmpty()) "Load" else "Reload")
            }
        }
        if (!connected && ui.apps.isEmpty()) {
            Text("Connect to the watch on the Watch tab, then tap Load. Uses read-only 'pm list packages' commands.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        ui.appsError?.let { Text(it, color = Danger, fontSize = 13.sp) }
        if (ui.appsLoading) {
            CircularProgressIndicator(Modifier.padding(16.dp)); return
        }
        if (ui.apps.isEmpty()) return

        OutlinedTextField(query, { query = it }, label = { Text("Search packages") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${ui.apps.size}") })
            AppCategory.entries.forEach { cat ->
                val n = ui.apps.count { it.classification.category == cat }
                FilterChip(selected = filter == cat, onClick = { filter = cat }, label = { Text("${cat.short} $n") })
            }
        }
        if (filter == AppCategory.OPTIONAL_SAMSUNG && ui.apps.none { it.classification.category == AppCategory.OPTIONAL_SAMSUNG }) {
            Text("The verified-optional allowlist is intentionally empty until packages are checked on your exact watch. " +
                "Unverified Samsung packages appear under Unknown.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val shown = ui.apps.filter {
            (filter == null || it.classification.category == filter) &&
                (query.isBlank() || it.packageName.contains(query, true) || it.displayName.contains(query, true))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.packageName }) { AppRow(it) { vm.openDetail(it) } }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.displayName, fontWeight = FontWeight.SemiBold,
                fontStyle = if (app.nameIsDerived) FontStyle.Italic else FontStyle.Normal)
            Text(app.packageName, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(app.classification.category.short, categoryColor(app.classification.category))
                Badge(if (app.isSystem) "System" else "User", Magenta)
                when {
                    !app.installedForUser -> Badge("Removed for user", Danger)
                    !app.isEnabled -> Badge("Disabled", Amber)
                    else -> Badge("Enabled", Neon)
                }
            }
        }
    }
}

@Composable
fun PackageDetailScreen(state: DetailState, ui: UiState, vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val app = state.entry
    val cl = state.classification
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column {
                Text(app.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(app.packageName, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (app.nameIsDerived) Text("Display name is derived from the package id — app labels aren't readable over plain ADB.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard("Classification") {
            Badge(cl.category.title, categoryColor(cl.category))
            cl.reasons.forEach { Text("• $it", fontSize = 13.sp) }
            Text(
                if (cl.modifiable) "Eligible for Custom Mode, with explicit confirmation."
                else "Not eligible for modification. This app will never offer to disable it.",
                fontSize = 12.sp, color = if (cl.modifiable) Neon else Danger,
            )
            val connected = ui.conn is ConnState.Connected
            val d0 = state.details
            when {
                !app.installedForUser -> OutlinedButton(onClick = { vm.proposeReinstall(app) }, enabled = connected && !ui.planning) {
                    Text("Reinstall for this user…")
                }
                d0?.user0EnabledState == 3 -> OutlinedButton(onClick = { vm.proposeEnable(app) }, enabled = connected && !ui.planning) {
                    Text("Re-enable…")
                }
                cl.modifiable && app.isEnabled && !state.loading -> OutlinedButton(onClick = { vm.proposeDisable(app) }, enabled = connected && !ui.planning) {
                    Text("Disable… (Custom Mode)")
                }
            }
            Text("Every action opens a review with the exact command, risks and undo before anything runs.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = Danger) }
        state.details?.let { d ->
            SectionCard("Package information") {
                InfoRow("Version", d.versionName?.let { "$it (${d.versionCode ?: "?"})" })
                InfoRow("Min / target SDK", "${d.minSdk ?: "?"} / ${d.targetSdk ?: "?"}")
                InfoRow("System / user", if (app.isSystem) "System" else "User-installed")
                InfoRow("State", when { !app.installedForUser -> "Removed for user 0"; !app.isEnabled -> "Disabled"; else -> "Enabled" })
                InfoRow("Installed", d.firstInstallTime)
                InfoRow("Updated", d.lastUpdateTime)
                InfoRow("Installer", d.installer)
                InfoRow("Code path", d.codePath)
                if (d.flags.isNotEmpty()) InfoRow("Flags", d.flags.joinToString(" "))
            }
            SectionCard("Known dependencies") {
                if (d.knownDependencies.isEmpty()) Text("None reported by dumpsys. Absence here does NOT mean nothing depends on it.",
                    fontSize = 13.sp)
                d.knownDependencies.forEach { Text("• $it", fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
            }
            SectionCard("Requested permissions (${d.requestedPermissions.size})") {
                d.requestedPermissions.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
