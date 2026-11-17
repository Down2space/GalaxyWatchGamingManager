package com.gwgm.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gwgm.manager.ui.theme.Amber
import com.gwgm.manager.ui.theme.Neon

private val callingChecklist = listOf(
    "Phone's Bluetooth OFF: place an LTE call from the watch to a friend",
    "Receive an incoming LTE call on the watch and answer it",
    "Other person can hear you (watch microphone)",
    "You can hear them (watch speaker)",
    "Phone's Bluetooth ON: place a call from the watch through the phone",
    "Galaxy Wearable app shows the watch as Connected",
    "Watch shows mobile signal / carrier on the eSIM",
    "Send and receive a text message on the watch",
    "Watch connects to Wi-Fi",
    "Watch charges on its charger",
    "Emergency SOS settings still present (do NOT dial an emergency number to test)",
)

@Composable
fun SafetyScreen(ui: UiState, vm: MainViewModel) {
    val checked = remember { mutableStateListOf<Int>() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SAFETY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)

        SectionCard("This build (phases 1–4)") {
            Text("• Pairs and connects over wireless ADB (6-digit code, TLS).\n" +
                "• Reads watch info and the installed-package list.\n" +
                "• Classifies every package; protection rules always win.\n" +
                "• Read commands pass a read-only allowlist. Changes go through a separate path that only accepts " +
                "7 reversible templates (disable/enable/reinstall a package, animation scales, clear cached apps, open the game hub).\n" +
                "• Never: uninstall, clear data, reboot, or touch telephony, Bluetooth, Wi-Fi, LTE or launcher settings.",
                fontSize = 13.sp)
        }

        SectionCard("Always protected") {
            Text(
                listOf(
                    "Cellular modem and eSIM", "Phone calls and telephony", "Bluetooth", "Galaxy Wearable pairing",
                    "Wi-Fi networking", "Audio and microphone", "Touchscreen and display", "GPU and graphics",
                    "Battery and charging", "Android framework", "Package management", "Launcher, setup and recovery/updates",
                ).joinToString("\n") { "• $it" },
                fontSize = 13.sp,
            )
            Text("Matching is deliberately broad. Anything not positively verified as optional is treated as unsafe.",
                fontSize = 12.sp, color = Amber)
        }

        SectionCard("Before any future change, the app will") {
            Text(
                listOf(
                    "Verify the watch is connected", "Re-read the package inventory", "Check protection rules",
                    "Show the exact ADB command", "Explain the risks", "Ask for confirmation",
                    "Run only the approved command", "Record the original state and result", "Offer the restore command",
                ).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
                fontSize = 13.sp,
            )
        }

        SectionCard("Calling-function test checklist") {
            Text("Run this after ANY change to the watch.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            callingChecklist.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = i in checked, onCheckedChange = { if (it) checked += i else checked -= i })
                    Text(item, fontSize = 13.sp)
                }
            }
            if (ui.checklistDue) Text("Due: a change was made since the last completed checklist.", fontSize = 12.sp, color = Amber)
            if (checked.size == callingChecklist.size) {
                Text("All checks passed.", color = Neon)
                if (ui.checklistDue) Button(onClick = vm::checklistCompleted) { Text("Mark checklist complete") }
            }
            Text("If any check fails: Optimize › Standard Mode › restore, then re-test.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Limitations") {
            Text(
                "• App names: plain ADB can't read app labels, so names are derived from package ids.\n" +
                    "• Wear OS version is estimated from the Android API level.\n" +
                    "• Dependencies show only what dumpsys states (libraries, shared UID). Hidden runtime dependencies can't be detected.\n" +
                    "• Gaming Mode can't raise CPU/GPU clocks or change thermal limits — that needs root. Its gains are modest: snappier UI and more free RAM.\n" +
                    "• ADB cannot touch the modem firmware, bootloader, kernel or CPU/GPU clocks — no app can without root.\n" +
                    "• Wireless debugging turns itself off after reboots and on some network changes; you'll need to reconnect (re-pairing is usually not needed).",
                fontSize = 13.sp,
            )
        }
    }
}
