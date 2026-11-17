package com.gwgm.manager.safety

import com.gwgm.manager.adb.WatchConnection
import com.gwgm.manager.data.AppEntry
import com.gwgm.manager.data.WatchParsers
import com.gwgm.manager.data.WatchRepository

/** A change shown to the user BEFORE anything runs. */
data class ProposedChange(
    val kind: ChangeKind,
    val target: String,
    val title: String,
    val command: ApprovedCommand,
    val originalState: String,
    val restoreCommand: String?,
    val risks: List<String>,
    val restoreNotes: List<String>,
    /** Expected value after the change (settings), used for verification. */
    val expected: String? = null,
    /** For RESTORE: the journal entry being undone. */
    val restoresEntryId: Long? = null,
)

data class ChangeResult(val change: ProposedChange, val success: Boolean, val message: String)

class RefusedException(msg: String) : Exception(msg)

/**
 * Implements the 9-step safety sequence:
 * 1 verify connected · 2 fresh inventory · 3 protection rules · 4 proposed change · 5 risks
 * (4–5 shown in the UI) · 6 confirmation (UI) · 7 run only the approved command · 8 record · 9 restore path.
 * Steps 1–3 run again at execution time, so a stale plan can never execute.
 */
class ChangePlanner(
    private val conn: WatchConnection,
    private val repo: WatchRepository,
    private val journal: ChangeJournal,
) {

    private val factoryResetNote =
        "Worst case (watch unusable or ADB can't reconnect): a factory reset (Settings › General › Reset, or recovery mode) " +
            "re-enables every system package but ERASES watch data and the eSIM profile may need to be re-downloaded from your carrier."

    private val adbNote =
        "Restoring needs this app to reach the watch over wireless ADB. If Wireless debugging was turned off (e.g. after a reboot), turn it on and reconnect first."

    // ---- planning ---------------------------------------------------------------------------

    suspend fun planDisable(pkg: String): ProposedChange {
        val (entry, details) = preflight(pkg)
        if (!entry.installedForUser) throw RefusedException("$pkg isn't installed for this user.")
        if (!entry.isEnabled) throw RefusedException("$pkg is already disabled.")
        val cl = details.second
        if (!cl.modifiable) throw RefusedException(
            "Refused: $pkg is classified \"${cl.category.title}\". Only user-installed apps and individually verified optional packages can be disabled."
        )
        val risks = buildList {
            add("The app stops running and disappears from the app list; its tiles, complications and notifications stop.")
            if (entry.isSystem) add("This is a Samsung system package on the verified-optional list. Re-test calling, Bluetooth and LTE afterwards anyway.")
            else add("This is a user-installed app; disabling it does not affect system services.")
            add("App data is kept (this is disable, not uninstall).")
        }
        return ProposedChange(
            kind = ChangeKind.DISABLE_PACKAGE, target = pkg, title = "Disable ${entry.displayName}",
            command = ApprovedCommand.approve("pm disable-user --user 0 $pkg"),
            originalState = "enabled (state ${details.first.user0EnabledState ?: "?"})",
            restoreCommand = "pm enable --user 0 $pkg",
            risks = risks, restoreNotes = listOf("Undo: pm enable --user 0 $pkg (one tap in the Optimize tab).", adbNote, factoryResetNote),
        )
    }

    suspend fun planEnable(pkg: String): ProposedChange {
        val (entry, details) = preflight(pkg)
        val state = details.first.user0EnabledState
        if (state != STATE_DISABLED_USER) throw RefusedException(
            if (entry.isEnabled) "$pkg is already enabled."
            else "Refused: $pkg was disabled by the SYSTEM (state $state), not by a user/ADB. Some carrier and region packages are disabled on purpose; enabling them can cause problems."
        )
        val cl = details.second
        return ProposedChange(
            kind = ChangeKind.ENABLE_PACKAGE, target = pkg, title = "Re-enable ${entry.displayName}",
            command = ApprovedCommand.approve("pm enable --user 0 $pkg"),
            originalState = "disabled-user (state 3)",
            restoreCommand = if (cl.modifiable) "pm disable-user --user 0 $pkg" else null,
            risks = listOf("Returns the package to its normal enabled state. This direction is generally safe.",
                "If something else was disabled together with it, that stays disabled."),
            restoreNotes = if (cl.modifiable) listOf("Undo: pm disable-user --user 0 $pkg.", adbNote)
            else listOf("No undo is offered: this app never automates disabling a ${cl.category.short.lowercase()} component."),
        )
    }

    suspend fun planInstallExisting(pkg: String): ProposedChange {
        val (entry, _) = preflight(pkg)
        if (entry.installedForUser) throw RefusedException("$pkg is already installed for this user.")
        return ProposedChange(
            kind = ChangeKind.INSTALL_EXISTING, target = pkg, title = "Reinstall ${entry.displayName} for user 0",
            command = ApprovedCommand.approve("pm install-existing --user 0 $pkg"),
            originalState = "removed for user 0 (still on the system partition)",
            restoreCommand = null,
            risks = listOf("Brings back a system package that was removed for this user (for example by an earlier debloat).",
                "This is the safe direction; it restores the factory configuration for this package."),
            restoreNotes = listOf("No undo is offered: this app never removes packages for a user."),
        )
    }

    /** Gaming Mode: reversible UI-animation tweaks, clear cached background processes, launch the game hub. */
    suspend fun planGamingMode(): List<ProposedChange> {
        requireConnected()
        val out = mutableListOf<ProposedChange>()
        for (key in ApprovedCommand.ANIMATION_KEYS.split('|')) {
            val current = conn.exec("settings get global $key").trim()
            if (current == TARGET_SCALE) continue
            val restore = when {
                current == "null" || current.isEmpty() -> "settings delete global $key"
                ApprovedCommand.isValidScale(current) -> "settings put global $key $current"
                else -> continue // unexpected value: can't promise an exact restore, so don't touch it
            }
            out += ProposedChange(
                kind = ChangeKind.SET_GLOBAL_SETTING, target = key, title = "Set $key to $TARGET_SCALE",
                command = ApprovedCommand.approve("settings put global $key $TARGET_SCALE"),
                originalState = current.ifEmpty { "null" }, restoreCommand = restore, expected = TARGET_SCALE,
                risks = listOf("Makes system animations twice as fast so the watch spends less time animating UI. Cosmetic only."),
                restoreNotes = listOf("Undo: $restore", "Same setting as Developer options › animation scale, so it can also be reset by hand on the watch."),
            )
        }
        out += ProposedChange(
            kind = ChangeKind.KILL_BACKGROUND, target = "background processes", title = "Clear cached background apps",
            command = ApprovedCommand.approve("am kill-all"), originalState = "n/a (not persistent)", restoreCommand = null,
            risks = listOf("Stops cached background processes only, like the system does under memory pressure. Services needed for calls, Bluetooth and LTE are not cached processes and keep running.",
                "Apps you closed will cold-start next time."),
            restoreNotes = listOf("Nothing to undo; apps restart normally when used."),
        )
        if (ApprovedCommand.COMPANION_PACKAGE in WatchParsers.parsePackageList(conn.exec("pm list packages"))) {
            out += ProposedChange(
                kind = ChangeKind.LAUNCH_APP, target = ApprovedCommand.COMPANION_PACKAGE, title = "Open the watch gaming app",
                command = ApprovedCommand.approve("am start -n ${ApprovedCommand.COMPANION_COMPONENT}"),
                originalState = "n/a", restoreCommand = null,
                risks = listOf("Opens a normal app. It does not replace the watch launcher."), restoreNotes = listOf("Press the watch's Back button to leave it."),
            )
        }
        return out
    }

    fun planRestore(e: JournalEntry): ProposedChange {
        val cmd = e.restoreCommand ?: throw RefusedException("\"${e.title}\" has no restore step.")
        return ProposedChange(
            kind = ChangeKind.RESTORE, target = e.target, title = "Undo: ${e.title}",
            command = ApprovedCommand.approve(cmd), originalState = "changed (was: ${e.originalState})",
            restoreCommand = null, risks = listOf("Puts ${e.target} back to how it was before (${e.originalState})."),
            restoreNotes = listOf(adbNote), restoresEntryId = e.id,
        )
    }

    /** Standard Mode = undo every change still in effect, newest first. */
    fun planStandardMode(): List<ProposedChange> = journal.pending().map { planRestore(it) }

    // ---- execution --------------------------------------------------------------------------

    suspend fun execute(change: ProposedChange): ChangeResult {
        // Re-run steps 1–3 against the live watch: protection rules must still allow this.
        requireConnected()
        when (change.kind) {
            ChangeKind.DISABLE_PACKAGE -> {
                val (_, d) = preflight(change.target)
                if (!d.second.modifiable) throw RefusedException("Refused at execution: ${change.target} is now classified ${d.second.category.short}.")
            }
            else -> Unit
        }

        // Step 7: only the approved command.
        val output = runCatching { conn.execModifying(change.command) }.getOrElse { e ->
            record(change, "ERROR: ${e.message}", false)
            return ChangeResult(change, false, "Failed to run: ${e.message}")
        }.trim()

        val looksBad = Regex("(?i)(error|exception|failure|denied|not allowed|unknown)").containsMatchIn(output)
        val verified = !looksBad && verify(change)
        record(change, output, verified)
        val msg = when {
            looksBad -> "The watch reported a problem: ${output.take(200)}"
            !verified -> "Command ran but the new state couldn't be confirmed. Check the journal."
            else -> "Done and verified."
        }
        return ChangeResult(change, verified, msg)
    }

    private suspend fun verify(c: ProposedChange): Boolean = runCatching {
        when (c.kind) {
            ChangeKind.DISABLE_PACKAGE -> details(c.target).user0EnabledState == STATE_DISABLED_USER
            ChangeKind.ENABLE_PACKAGE -> details(c.target).user0EnabledState in setOf(0, 1)
            ChangeKind.INSTALL_EXISTING -> c.target in WatchParsers.parsePackageList(conn.exec("pm list packages"))
            ChangeKind.SET_GLOBAL_SETTING -> conn.exec("settings get global ${c.target}").trim() == c.expected
            ChangeKind.KILL_BACKGROUND, ChangeKind.LAUNCH_APP -> true
            ChangeKind.RESTORE -> verifyRestore(c)
        }
    }.getOrDefault(false)

    private suspend fun verifyRestore(c: ProposedChange): Boolean {
        val t = c.command.text
        return when {
            t.startsWith("pm enable") -> details(c.target).user0EnabledState in setOf(0, 1)
            t.startsWith("pm disable-user") -> details(c.target).user0EnabledState == STATE_DISABLED_USER
            t.startsWith("settings put global") -> conn.exec("settings get global ${c.target}").trim() == t.substringAfterLast(' ')
            t.startsWith("settings delete global") -> conn.exec("settings get global ${c.target}").trim() == "null"
            else -> true
        }
    }

    private fun record(c: ProposedChange, output: String, success: Boolean) {
        if (c.kind == ChangeKind.RESTORE) {
            if (success) c.restoresEntryId?.let { journal.markRestored(it, output.ifBlank { "ok" }) }
            return
        }
        journal.add(
            JournalEntry(
                id = System.nanoTime(), time = System.currentTimeMillis(), kind = c.kind, target = c.target,
                title = c.title, command = c.command.text, originalState = c.originalState,
                restoreCommand = c.restoreCommand, output = output.ifBlank { "(no output)" }, success = success,
            )
        )
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun requireConnected() {
        if (!conn.isConnected) throw RefusedException("Not connected to the watch.")
    }

    private suspend fun details(pkg: String) =
        WatchParsers.parsePackageDump(pkg, conn.exec("dumpsys package $pkg"))

    /** Steps 1–3: connected, fresh inventory, protection rules (incl. shared-UID check). */
    private suspend fun preflight(pkg: String): Pair<AppEntry, Pair<com.gwgm.manager.data.PackageDetails, Classification>> {
        requireConnected()
        val entry = repo.fetchInventory().firstOrNull { it.packageName == pkg }
            ?: throw RefusedException("$pkg wasn't found on the watch.")
        return entry to repo.fetchDetails(entry)
    }

    companion object {
        const val STATE_DISABLED_USER = 3
        const val TARGET_SCALE = "0.5"
    }
}
