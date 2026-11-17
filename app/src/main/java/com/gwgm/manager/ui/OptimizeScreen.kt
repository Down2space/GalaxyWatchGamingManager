package com.gwgm.manager.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gwgm.manager.adb.ConnState
import com.gwgm.manager.safety.ProposedChange
import com.gwgm.manager.ui.theme.Amber
import com.gwgm.manager.ui.theme.Danger
import com.gwgm.manager.ui.theme.Neon
import java.text.DateFormat
import java.util.Date

@Composable
fun OptimizeScreen(ui: UiState, vm: MainViewModel, openChecklist: () -> Unit) {
    val connected = ui.conn is ConnState.Connected
    val clipboard = LocalClipboardManager.current
    val modifiable = ui.apps.filter { it.classification.modifiable && it.isEnabled && it.installedForUser }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OPTIMIZE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
        if (!connected) Text("Connect to the watch to make changes. The journal and restore commands below are always available.",
            fontSize = 12.sp, color = Amber)
        if (ui.planning) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
            Text("Checking the watch and protection rules…")
        }
        if (ui.checklistDue) SectionCard("Re-test calling") {
            Text("A change was made. Run the calling checklist before relying on the watch.", fontSize = 13.sp, color = Amber)
            Button(onClick = openChecklist) { Text("Open checklist") }
        }

        SectionCard("Mode") {
            OpMode.entries.forEach { m ->
                val selected = ui.mode == m
                Column(
                    Modifier.fillMaxWidth()
                        .border(1.dp, if (selected) Neon else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, fontWeight = FontWeight.Bold, color = if (selected) Neon else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        if (selected) Badge("ACTIVE", Neon)
                    }
                    Text(m.blurb, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (m) {
                        OpMode.STANDARD -> OutlinedButton(onClick = vm::proposeStandardMode, enabled = connected && !ui.planning) {
                            Text("Review & restore original state")
                        }
                        OpMode.GAMING -> OutlinedButton(onClick = vm::proposeGamingMode, enabled = connected && !ui.planning) {
                            Text("Review Gaming Mode changes")
                        }
                        OpMode.CUSTOM -> Text(
                            if (ui.apps.isEmpty()) "Load the inventory on the Apps tab to see eligible packages."
                            else "${modifiable.size} eligible package(s) below. Protected and Unknown packages are never listed.",
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (modifiable.isNotEmpty()) SectionCard("Custom Mode — eligible packages") {
            modifiable.forEach { app ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName, fontSize = 14.sp)
                        Text(app.packageName, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.proposeDisable(app) }, enabled = connected && !ui.planning) { Text("Disable…") }
                }
            }
        }

        SectionCard("Change journal") {
            if (ui.journal.isEmpty()) Text("No changes made yet.", fontSize = 13.sp)
            ui.journal.forEach { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        when {
                            !e.success -> Badge("FAILED", Danger)
                            e.restored -> Badge("RESTORED", Neon)
                            e.restoreCommand == null -> Badge("NO UNDO NEEDED", MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Badge("IN EFFECT", Amber)
                        }
                    }
                    Text(DateFormat.getDateTimeInstance().format(Date(e.time)), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ran: ${e.command}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("was: ${e.originalState}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    e.restoreCommand?.let { Text("undo: $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                    if (e.needsRestore) TextButton(onClick = { vm.proposeRestore(e) }, enabled = connected && !ui.planning) { Text("Undo…") }
                }
            }
            if (ui.journal.any { it.needsRestore }) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.manualRestoreScript())) }) {
                    Text("Copy manual restore commands")
                }
                Text("Backup plan: paste these into any ADB tool (e.g. Bugjaeger) if this app can't connect.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Steps 4–9 of the safety sequence, shown for every change set. */
@Composable
fun ChangeReviewDialog(r: ReviewState, vm: MainViewModel, openChecklist: () -> Unit) {
    AlertDialog(
        onDismissRequest = vm::dismissReview,
        title = { Text(r.heading) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                r.changes.forEachIndexed { i, ch ->
                    val res = r.results.getOrNull(i)
                    ChangeBlock(i + 1, ch)
                    res?.let { Text(if (it.success) "✓ ${it.message}" else "✗ ${it.message}", color = if (it.success) Neon else Danger, fontSize = 13.sp) }
                }
                if (!r.done) Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = !r.running) { vm.setAcknowledged(!r.acknowledged) }) {
                    Checkbox(checked = r.acknowledged, onCheckedChange = vm::setAcknowledged, enabled = !r.running)
                    Text("I've read the exact commands, risks and how to undo them.", fontSize = 13.sp)
                }
                if (r.running) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Running…")
                }
                if (r.done) Text(
                    if (r.results.all { it.success } && r.results.size == r.changes.size) "All changes applied and recorded in the journal."
                    else "Stopped. Nothing after the failed step was run. Every step that did run is in the journal.",
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            if (r.done) Button(onClick = { vm.dismissReview(); if (r.results.any { it.success }) openChecklist() }) {
                Text(if (r.results.any { it.success }) "Run calling checklist" else "Close")
            } else Button(onClick = vm::confirmReview, enabled = r.acknowledged && !r.running) {
                Text("Run ${r.changes.size} command${if (r.changes.size == 1) "" else "s"}")
            }
        },
        dismissButton = { if (!r.running && !r.done) TextButton(onClick = vm::dismissReview) { Text("Cancel") } },
    )
}

@Composable
private fun ChangeBlock(n: Int, ch: ProposedChange) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$n. ${ch.title}", fontWeight = FontWeight.Bold)
        Text("Current state: ${ch.originalState}", fontSize = 12.sp)
        Text("Command:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("adb shell ${ch.command.text}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Neon)
        Text("Risks:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ch.risks.forEach { Text("• $it", fontSize = 12.sp) }
        Text("Restoring:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ch.restoreNotes.forEach { Text("• $it", fontSize = 12.sp) }
    }
}
