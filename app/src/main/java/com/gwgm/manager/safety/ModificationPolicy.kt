package com.gwgm.manager.safety

/**
 * A shell command that may change watch state. It can ONLY be created through [approve], which
 * checks it against a short, fixed list of reversible command templates. There is no way to send
 * free-form text through the modifying path.
 *
 * Deliberately NOT supported, ever: pm uninstall, pm clear, rm, reboot, settings on secure
 * telephony/radio keys, svc, cmd phone, anything touching Bluetooth, Wi-Fi, LTE or the launcher.
 */
class ApprovedCommand private constructor(val text: String) {

    override fun toString() = text

    companion object {
        private const val PKG = "[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"
        const val ANIMATION_KEYS = "window_animation_scale|transition_animation_scale|animator_duration_scale"
        private const val SCALE = "(0|0\\.[0-9]+|[1-9](\\.[0-9]+)?|10)"

        const val COMPANION_PACKAGE = "com.gwgm.watch"
        const val COMPANION_COMPONENT = "com.gwgm.watch/.MainActivity"

        private val templates = listOf(
            Regex("pm disable-user --user 0 $PKG"),
            Regex("pm enable --user 0 $PKG"),
            Regex("pm install-existing --user 0 $PKG"),
            Regex("settings put global ($ANIMATION_KEYS) $SCALE"),
            Regex("settings delete global ($ANIMATION_KEYS)"),
            Regex("am kill-all"),
            // Open any app's launcher screen (doesn't change anything persistent).
            Regex("am start -n $PKG/[A-Za-z0-9_.]+"),
            // Install/update a user app from an APK streamed over ADB (exec: service, no shell).
            Regex("cmd package install -r -t -S [1-9][0-9]{0,10}"),
            // Undo of an install THIS app made (planner only allows it for journaled INSTALL_APK entries).
            Regex("pm uninstall $PKG"),
        )

        private val forbiddenChars = Regex("[;&|`$<>\\\\\\n\\r]")

        fun isAllowed(cmd: String) = !forbiddenChars.containsMatchIn(cmd) && templates.any { it.matches(cmd) }

        fun approve(cmd: String): ApprovedCommand {
            if (!isAllowed(cmd)) throw SecurityException("Blocked: \"$cmd\" is not an approved reversible command.")
            return ApprovedCommand(cmd)
        }

        fun isValidScale(v: String) = Regex(SCALE).matches(v)
    }
}
