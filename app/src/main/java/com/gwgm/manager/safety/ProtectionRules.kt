package com.gwgm.manager.safety

import android.content.Context
import org.json.JSONObject

enum class AppCategory(val title: String, val short: String) {
    PROTECTED("Protected essential system component", "Protected"),
    OPTIONAL_SAMSUNG("Optional Samsung service (verified)", "Optional"),
    USER("User-installed application", "User app"),
    UNKNOWN("Unknown or unverified component", "Unknown"),
}

data class Classification(val category: AppCategory, val reasons: List<String>) {
    /** Only verified-optional and user apps can EVER be offered for changes (Phase 4+). */
    val modifiable: Boolean get() = category == AppCategory.OPTIONAL_SAMSUNG || category == AppCategory.USER
}

data class OptionalEntry(val packageName: String, val description: String, val verifiedOn: String)

/**
 * Conservative classifier. Protection always wins; anything not positively identified is UNKNOWN
 * (never assumed safe). Keyword rules are deliberately broad — a false "protected" costs nothing,
 * a false "optional" could cost you calling.
 */
class ProtectionRules(private val verifiedOptional: Map<String, OptionalEntry>) {

    private data class Rule(val area: String, val keywords: List<String>)

    private val exactProtected = mapOf(
        "android" to "Android system framework",
        "com.android.shell" to "ADB shell (this app's own access path)",
        "com.android.phone" to "Telephony / phone calls",
        "com.android.server.telecom" to "Call routing (Telecom)",
        "com.android.systemui" to "System UI (display, touch, notifications)",
        "com.android.settings" to "Settings (recovery path for every other change)",
        "com.google.android.gms" to "Google Play services (Wear OS core)",
        "com.google.android.gsf" to "Google Services Framework",
        "com.google.android.wearable.app" to "Wear OS system home",
        "com.android.packageinstaller" to "Package management",
        "com.google.android.packageinstaller" to "Package management",
        "com.android.permissioncontroller" to "Permission management",
        "com.google.android.permissioncontroller" to "Permission management",
        "com.android.inputdevices" to "Touchscreen / input",
        "com.android.location.fused" to "Location (used for emergency calls)",
    )

    private val protectedPrefixes = listOf(
        "android." to "Android system framework",
        "com.android.providers." to "Android system data provider",
        "com.android.server." to "Android system service",
    )

    private val protectedSharedUids = mapOf(
        "android.uid.system" to "Runs as the system user",
        "android.uid.phone" to "Runs as the telephony user",
        "android.uid.bluetooth" to "Runs as the Bluetooth user",
        "android.uid.nfc" to "Runs as the NFC/payment user",
        "android.uid.shell" to "Runs as the shell user",
        "android.uid.networkstack" to "Runs as the network stack user",
        "android.media" to "Runs as the media/audio user",
    )

    /** Keywords matched against package-name SEGMENTS/substrings of system packages. */
    private val rules = listOf(
        Rule("Cellular modem / eSIM", listOf("telephony", "euicc", "esim", "modem", "cellbroadcast", "carrier", "stk", "ims", "sim", "mms", "sms", "radio")),
        Rule("Carrier LTE / number sharing", listOf("verizon", "vzw", "tmobile", "tmo", "att", "sprint", "uscc", "numbersync", "digits", "callplus", "multiline", "wfc", "volte", "vowifi")),
        Rule("Phone calls", listOf("phone", "telecom", "dialer", "incallui", "call", "voip", "contacts")),
        Rule("Bluetooth", listOf("bluetooth", "bt")),
        Rule("Galaxy Wearable pairing", listOf("wearable", "companion", "accessory", "watchmanager", "gearmanager", "pairing", "setupwizard", "provision")),
        Rule("Wi-Fi / networking", listOf("wifi", "network", "networkstack", "connectivity", "tethering", "captiveportal", "dns", "vpn")),
        Rule("Audio / microphone", listOf("audio", "sound", "media", "voice", "mic", "speaker")),
        Rule("Touchscreen / display", listOf("display", "input", "touch", "systemui", "sysui", "watchface", "clockface", "overlay", "rro")),
        Rule("GPU / graphics", listOf("gpu", "graphics", "opengl", "vulkan", "angle", "gamedriver", "surfaceflinger")),
        Rule("Battery / charging", listOf("battery", "power", "charging", "charger", "thermal")),
        Rule("System framework / security", listOf("framework", "resources", "keychain", "keystore", "security", "knox", "biometric", "permission", "location", "time")),
        Rule("Package management", listOf("package", "installer", "shell")),
        Rule("Launcher / recovery / updates", listOf("launcher", "home", "recovery", "update", "ota", "fota", "setup")),
    )

    fun classify(pkg: String, isSystem: Boolean, sharedUserId: String? = null): Classification {
        val reasons = mutableListOf<String>()

        exactProtected[pkg]?.let { reasons += "Exact match: $it" }
        protectedPrefixes.firstOrNull { pkg.startsWith(it.first) }?.let { reasons += "Prefix '${it.first}': ${it.second}" }
        sharedUserId?.let { uid -> protectedSharedUids[uid]?.let { reasons += "Shared UID $uid: $it" } }
        // Applied to user-installed apps too: US carrier LTE/number-sharing companions are often
        // installed as regular apps, and disabling one can break calling on the watch.
        val lower = pkg.lowercase()
        val segments = lower.split('.', '_')
        for (rule in rules) {
            val hit = rule.keywords.firstOrNull { kw ->
                // Short keywords must match a whole segment ("sim" not inside "simple").
                if (kw.length <= 4) segments.contains(kw) else lower.contains(kw)
            }
            if (hit != null) reasons += "Keyword '$hit': ${rule.area}"
        }
        if (reasons.isNotEmpty()) return Classification(AppCategory.PROTECTED, reasons)

        verifiedOptional[pkg]?.let {
            return Classification(
                AppCategory.OPTIONAL_SAMSUNG,
                listOf("Individually verified optional package: ${it.description} (verified on ${it.verifiedOn})")
            )
        }

        if (!isSystem) return Classification(AppCategory.USER, listOf("Installed by the user (not part of the system image)"))

        return Classification(
            AppCategory.UNKNOWN,
            listOf("System package that is not on the verified-optional allowlist — treated as NOT safe to change")
        )
    }
}

object OptionalAllowlist {
    fun load(context: Context): Map<String, OptionalEntry> = runCatching {
        val json = context.assets.open("optional_allowlist.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).optJSONArray("verified") ?: return emptyMap()
        (0 until arr.length()).associate { i ->
            val o = arr.getJSONObject(i)
            val e = OptionalEntry(o.getString("package"), o.optString("description"), o.optString("verifiedOn"))
            e.packageName to e
        }
    }.getOrDefault(emptyMap())
}
