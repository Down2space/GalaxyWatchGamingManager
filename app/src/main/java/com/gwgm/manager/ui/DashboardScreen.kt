package com.gwgm.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gwgm.manager.adb.ConnState
import com.gwgm.manager.ui.theme.Amber
import com.gwgm.manager.ui.theme.Danger
import com.gwgm.manager.ui.theme.Neon

@Composable
fun DashboardScreen(ui: UiState, vm: MainViewModel) {
    var showPair by remember { mutableStateOf(false) }
    var showConnectInfo by remember { mutableStateOf(false) }
    val connected = ui.conn is ConnState.Connected
    val busy = ui.conn is ConnState.Busy

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "GALAXY WATCH GAMING MANAGER",
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, fontSize = 18.sp,
        )
        Text("Target: Galaxy Watch6 Classic LTE (SM-R965U)", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard("ADB connection") {
            ConnectionStatus(ui.conn)
            OutlinedTextField(
                value = ui.host, onValueChange = vm::setHost, singleLine = true,
                label = { Text("Watch IP address") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !busy && !connected,
            )
            OutlinedTextField(
                value = ui.connectPort, onValueChange = vm::setConnectPort, singleLine = true,
                label = { Text("Wireless debugging port (connect)") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !busy && !connected,
            )
            if (!connected) {
                TextButton(onClick = { vm.discover(pairing = false) }, enabled = !ui.discovering) {
                    Text(if (ui.discovering) "Searching…" else "Find watch on this Wi-Fi")
                }
                ui.connectFound.forEach { e ->
                    TextButton(onClick = { vm.useConnectEndpoint(e) }) { Text("Use ${e.name} — ${e.host}:${e.port}") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showPair = true }, enabled = !busy && !connected, modifier = Modifier.weight(1f)) { Text("Pair") }
                Button(
                    onClick = { if (ui.connectExplained) vm.connect() else showConnectInfo = true },
                    enabled = !busy && !connected, modifier = Modifier.weight(1f),
                ) { Text("Connect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.disconnect() }, enabled = connected || busy, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                OutlinedButton(onClick = vm::refresh, enabled = connected && !ui.infoLoading, modifier = Modifier.weight(1f)) { Text("Refresh") }
            }
            if (!ui.hasIdentity) {
                Text("Not paired yet. Tap Pair first — it's a one-time step per phone.", fontSize = 12.sp, color = Amber)
            }
        }

        SectionCard("Watch") {
            if (ui.infoLoading) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Reading watch…")
            }
            val i = ui.info
            InfoRow("Model", i?.let { listOfNotNull(it.marketName, it.model).distinct().joinToString(" · ").ifBlank { null } })
            InfoRow("Android", i?.let { x -> x.androidRelease?.let { r -> "$r (API ${x.sdk ?: "?"})" } })
            InfoRow("Wear OS", i?.wearOsEstimate?.let { "$it (estimated from API)" })
            InfoRow("One UI Watch", i?.oneUiWatch)
            InfoRow("Build", i?.buildId)
            InfoRow("Battery", i?.battery?.let { b ->
                buildString {
                    append(b.levelPercent?.let { "$it%" } ?: "?")
                    if (b.charging) append(" · charging${b.powerSource?.let { " ($it)" } ?: ""}")
                    b.temperatureC?.let { append(" · %.1f°C".format(it)) }
                }
            })
            InfoRow("RAM available", i?.memory?.let { "${formatKb(it.availableKb)} of ${formatKb(it.totalKb)}" })
            InfoRow("Storage free", i?.storage?.let { "${formatKb(it.availableKb)} of ${formatKb(it.totalKb)}" })
            InfoRow("Wi-Fi", i?.wifiStatus)
            InfoRow("ADB", when (val c = ui.conn) {
                is ConnState.Connected -> "Connected ${c.host}:${c.port}"
                is ConnState.Busy -> c.what
                is ConnState.Failed -> "Error"
                ConnState.Disconnected -> "Disconnected"
            })
            InfoRow("Installed apps", i?.appCount?.toString())
            i?.errors?.forEach { Text("⚠ $it", fontSize = 12.sp, color = Amber) }
        }

        if (ui.hasIdentity) {
            TextButton(onClick = vm::forgetIdentity, enabled = !busy) { Text("Forget this phone's pairing", color = Danger) }
        }
    }

    if (showPair) PairDialog(ui, vm, onDismiss = { showPair = false })

    if (showConnectInfo) AlertDialog(
        onDismissRequest = { showConnectInfo = false },
        title = { Text("Before connecting") },
        text = {
            Text(
                "• Connects to the watch's Wireless debugging port over your Wi-Fi using the TLS identity created during pairing.\n" +
                    "• This port is different from the pairing port and changes each time Wireless debugging is turned off and on.\n" +
                    "• The first time on a network, the watch may ask \"Allow wireless debugging on this network?\" — that's the watch's own authorization prompt.\n" +
                    "• Connecting and refreshing only run read-only commands: getprop, dumpsys battery, cat /proc/meminfo, df -k /data, cmd wifi status, pm list packages, dumpsys package <name>, settings get.\n" +
                    "• Nothing on the watch changes unless you review and confirm a specific change later."
            )
        },
        confirmButton = { Button(onClick = { showConnectInfo = false; vm.markConnectExplained(); vm.connect() }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { showConnectInfo = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ConnectionStatus(conn: ConnState) {
    val (color, text) = when (conn) {
        is ConnState.Connected -> Neon to "Connected to ${conn.host}:${conn.port}"
        is ConnState.Busy -> Amber to conn.what
        is ConnState.Failed -> Danger to conn.message
        ConnState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to "Not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 14.sp)
    }
}

@Composable
private fun PairDialog(ui: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var explained by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(ui.host) }
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    if (!explained) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("What pairing does") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "On the watch: Settings › Developer options › Wireless debugging › Pair new device. " +
                            "It shows a 6-digit code, an IP address and a pairing port.\n\n" +
                            "• This app sends that code to the watch over your Wi-Fi to set up TLS-authenticated trust.\n" +
                            "• The watch then lists this phone under Paired devices. You can remove it there at any time.\n" +
                            "• This phone's private key is stored encrypted with an Android Keystore key and is excluded from backups.\n" +
                            "• Pairing grants ADB shell access — the same level a computer running adb gets. No root, no bootloader unlock.\n" +
                            "• The app only changes something after showing you the exact command, its risks and how to undo it, and you confirm.\n\n" +
                            "Tip: keep the watch's Wi-Fi set to Always on while pairing — when it's near your phone it may switch to Bluetooth and drop Wi-Fi."
                    )
                }
            },
            confirmButton = { Button(onClick = { explained = true }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with watch") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Pairing port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit pairing code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                TextButton(onClick = { vm.discover(pairing = true) }, enabled = !ui.discovering) {
                    Text(if (ui.discovering) "Searching…" else "Find pairing port on this Wi-Fi")
                }
                Text("Searches your local network (mDNS) for devices showing the pairing screen. Nothing leaves your network.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.pairingFound.forEach { e ->
                    TextButton(onClick = { host = e.host; port = e.port.toString() }) { Text("${e.host}:${e.port}") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.pair(host, port, code); onDismiss() },
                enabled = host.isNotBlank() && port.isNotBlank() && code.length == 6,
            ) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
