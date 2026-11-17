package com.gwgm.manager.safety

import com.gwgm.manager.adb.WatchConnection
import com.gwgm.manager.data.ApkInfo
import com.gwgm.manager.data.ApkInspector
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
    /** For INSTALL_APK: the APK copied into the phone app's cache. */
    val apkPath: String? = null,
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

    /** Phase 6: install a game APK. All compatibility checks happen on the phone first. */
    suspend fun planInstallApk(apk: ApkInfo): ProposedChange {
        requireConnected()
        val props = WatchParsers.parseGetprop(conn.exec("getprop"))
        val watchSdk = props["ro.build.version.sdk"]?.toIntOrNull()
        if (watchSdk != null && apk.minSdk > watchSdk) throw RefusedException(
            "${apk.label} needs Android API ${apk.minSdk}, but the watch runs API $watchSdk. It can't install."
        )
        if (apk.abis.isNotEmpty() && apk.abis.none { it in ApkInspector.WATCH_ABIS }) throw RefusedException(
            "${apk.label} only contains native code for ${apk.abis.joinToString()} — the watch's processor is ARM, so it can't run."
        )
        val storage = WatchParsers.parseDf(conn.exec("df -k /data"))
        if (storage != null && storage.availableKb * 1024 < apk.sizeBytes * 3) throw RefusedException(
            "Not enough free space on the watch: the APK is ${apk.sizeBytes / 1_048_576} MB and needs roughly 3× that free during install."
        )
        val installed = WatchParsers.parsePackageList(conn.exec("pm list packages"))
        val isUpdate = apk.packageName in installed
        if (isUpdate) {
            val (_, d) = preflight(apk.packageName)
            if (d.second.category != AppCategory.USER) throw RefusedException(
                "Refused: ${apk.packageName} is already on the watch as a ${d.second.category.short.lowercase()} package. Replacing system components isn't allowed."
            )
        }
        val risks = buildList {
            if (!apk.isWatchApp) add("⚠ This APK does NOT say it's made for watches. Phone games often don't fit the round screen, need gestures or hardware the watch lacks, or won't open at all. It's harmless to try and easy to uninstall.")
            else add("Built for Wear OS (declares the watch feature).")
            if (apk.standalone == false) add("Marked as NOT standalone: it may need a companion app on your phone to work.")
            add("Installs as a normal user app (${apk.sizeBytes / 1_048_576} MB). System apps, calling, Bluetooth and LTE are untouched.")
            if (isUpdate) add("Updates the existing ${apk.label} and keeps its data.")
            add("ADB installs skip Play Protect scanning on the watch — only install APKs from sources you trust.")
        }
        return ProposedChange(
            kind = ChangeKind.INSTALL_APK, target = apk.packageName,
            title = "${if (isUpdate) "Update" else "Install"} ${apk.label} ${apk.versionName ?: ""}".trim(),
            command = ApprovedCommand.approve("cmd package install -r -t -S ${apk.sizeBytes}"),
            originalState = if (isUpdate) "installed (older version)" else "not installed",
            restoreCommand = if (isUpdate) null else "pm uninstall ${apk.packageName}",
            risks = risks,
            restoreNotes = if (isUpdate) listOf("No automatic undo for updates (Android can't downgrade without uninstalling). You can uninstall it on the watch.")
            else listOf("Undo: pm uninstall ${apk.packageName} (removes the game and its data). You can also uninstall it from the watch's app list."),
            apkPath = apk.file.path,
        )
    }

    /** Opens an app's launcher screen on the watch. Not a persistent change, so no journal entry. */
    suspend fun launch(pkg: String) {
        requireConnected()
        val out = conn.exec("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg")
        val comp = out.lines().map { it.trim() }.lastOrNull { it.startsWith("$pkg/") }
            ?: throw RefusedException("$pkg has no screen that can be opened from the app list.")
        val res = conn.execModifying(ApprovedCommand.approve("am start -n $comp"))
        if (Regex("(?i)(error|exception)").containsMatchIn(res)) throw RefusedException(res.trim().take(200))
    }

    fun planRestore(e: JournalEntry): ProposedChange {
        val cmd = e.restoreCommand ?: throw RefusedException("\"${e.title}\" has no restore step.")
        if (cmd.startsWith("pm uninstall") && e.kind != ChangeKind.INSTALL_APK)
            throw RefusedException("Uninstall is only allowed to undo an install this app made.")
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
        when {
            change.kind == ChangeKind.DISABLE_PACKAGE -> {
                val (_, d) = preflight(change.target)
                if (!d.second.modifiable) throw RefusedException("Refused at execution: ${change.target} is now classified ${d.second.category.short}.")
            }
            change.kind == ChangeKind.RESTORE && change.command.text.startsWith("pm uninstall") -> {
                val (_, d) = preflight(change.target)
                if (d.second.category != AppCategory.USER) throw RefusedException("Refused: ${change.target} is no longer a plain user app.")
            }
        }

        // Step 7: only the approved command.
        val output = runCatching {
            if (change.kind == ChangeKind.INSTALL_APK) {
                val f = java.io.File(change.apkPath ?: throw RefusedException("APK file missing."))
                if (!f.exists()) throw RefusedException("The selected APK is no longer available — pick it again.")
                conn.installApk(f, change.command)
            } else conn.execModifying(change.command)
        }.getOrElse { e ->
            record(change, "ERROR: ${e.message}", false)
            return ChangeResult(change, false, "Failed to run: ${e.message}")
        }.trim()

        val looksBad = Regex("(?i)(error|exception|failure|denied|not allowed|unknown)").containsMatchIn(output) ||
            (change.kind == ChangeKind.INSTALL_APK && !output.contains("Success"))
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
            ChangeKind.INSTALL_APK -> c.target in WatchParsers.parsePackageList(conn.exec("pm list packages -3"))
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
            t.startsWith("pm uninstall") -> c.target !in WatchParsers.parsePackageList(conn.exec("pm list packages"))
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
