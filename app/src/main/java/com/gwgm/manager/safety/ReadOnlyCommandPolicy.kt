package com.gwgm.manager.safety

/**
 * Hard gate in front of every shell command. In this build (Phases 1-2 + protection rules) ONLY
 * the read-only commands below can be sent to the watch. Modifying commands (pm disable-user,
 * pm install, settings put, …) will be added in Phase 4 behind a separate, confirmation-gated
 * path — never through this one.
 */
object ReadOnlyCommandPolicy {

    private const val PKG = "(android|[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+)"

    private val allowed = listOf(
        Regex("echo gwgm_ping"),
        Regex("getprop"),
        Regex("dumpsys battery"),
        Regex("cat /proc/meminfo"),
        Regex("df -k /data"),
        Regex("cmd wifi status"),
        Regex("pm list packages( -[3sdu])?"),
        Regex("dumpsys package $PKG"),
        Regex("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG"),
        Regex("settings get global (${ApprovedCommand.ANIMATION_KEYS})"),
    )

    /** Shell metacharacters are rejected outright so nothing can be chained onto an allowed command. */
    private val forbiddenChars = Regex("[;&|`$<>\\\\\\n\\r]")

    fun isAllowed(command: String): Boolean =
        !forbiddenChars.containsMatchIn(command) && allowed.any { it.matches(command) }

    fun requireAllowed(command: String) {
        if (!isAllowed(command)) {
            throw SecurityException("Blocked by read-only policy: \"$command\" is not an approved read-only command.")
        }
    }
}
