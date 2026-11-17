package com.gwgm.manager.safety

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ChangeKind { DISABLE_PACKAGE, ENABLE_PACKAGE, INSTALL_EXISTING, SET_GLOBAL_SETTING, KILL_BACKGROUND, LAUNCH_APP, RESTORE }

/** One executed change, with everything needed to understand and undo it. */
data class JournalEntry(
    val id: Long,
    val time: Long,
    val kind: ChangeKind,
    val target: String,
    val title: String,
    val command: String,
    val originalState: String,
    val restoreCommand: String?,
    val output: String,
    val success: Boolean,
    val restored: Boolean = false,
    val restoredAt: Long? = null,
    val restoreOutput: String? = null,
) {
    val needsRestore get() = success && !restored && restoreCommand != null
}

/** Persistent, append-mostly log of every change (app-private storage). */
class ChangeJournal(context: Context) {
    private val file = File(context.filesDir, "change_journal.json")

    @Synchronized
    fun all(): List<JournalEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(e: JournalEntry) = save(all() + e)

    @Synchronized
    fun markRestored(id: Long, output: String) =
        save(all().map { if (it.id == id) it.copy(restored = true, restoredAt = System.currentTimeMillis(), restoreOutput = output) else it })

    /** Changes still in effect, newest first (the order they must be undone in). */
    fun pending(): List<JournalEntry> = all().filter { it.needsRestore }.sortedByDescending { it.time }

    /** Plain text a person could run by hand (e.g. from another ADB tool) if this app can't. */
    fun manualRestoreScript(): String = pending().joinToString("\n") { "adb shell ${it.restoreCommand}   # ${it.title}" }
        .ifBlank { "# Nothing to restore." }

    private fun save(list: List<JournalEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString(2))
        tmp.renameTo(file)
    }

    private fun toJson(e: JournalEntry) = JSONObject().apply {
        put("id", e.id); put("time", e.time); put("kind", e.kind.name); put("target", e.target)
        put("title", e.title); put("command", e.command); put("originalState", e.originalState)
        put("restoreCommand", e.restoreCommand ?: JSONObject.NULL); put("output", e.output)
        put("success", e.success); put("restored", e.restored)
        put("restoredAt", e.restoredAt ?: JSONObject.NULL); put("restoreOutput", e.restoreOutput ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject) = JournalEntry(
        id = o.getLong("id"), time = o.getLong("time"), kind = ChangeKind.valueOf(o.getString("kind")),
        target = o.getString("target"), title = o.getString("title"), command = o.getString("command"),
        originalState = o.getString("originalState"),
        restoreCommand = if (o.isNull("restoreCommand")) null else o.getString("restoreCommand"),
        output = o.optString("output"), success = o.getBoolean("success"), restored = o.optBoolean("restored"),
        restoredAt = if (o.isNull("restoredAt")) null else o.getLong("restoredAt"),
        restoreOutput = if (o.isNull("restoreOutput")) null else o.getString("restoreOutput"),
    )
}
