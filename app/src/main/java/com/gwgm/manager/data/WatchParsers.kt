package com.gwgm.manager.data

/** Parsers for ADB shell output. Pure Kotlin — covered by unit tests. */
object WatchParsers {

    private val propLine = Regex("""^\[(.+?)]: \[(.*)]$""")

    fun parseGetprop(out: String): Map<String, String> =
        out.lineSequence().mapNotNull { propLine.find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }

    fun parseBattery(out: String): BatteryInfo {
        val kv = out.lineSequence().map { it.trim() }.filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val level = kv["level"]?.toIntOrNull()
        val scale = kv["scale"]?.toIntOrNull()?.takeIf { it > 0 } ?: 100
        val status = kv["status"]?.toIntOrNull()
        val source = listOf("AC", "USB", "Wireless", "Dock")
            .firstOrNull { kv["$it powered"] == "true" }
        return BatteryInfo(
            levelPercent = level?.let { it * 100 / scale },
            charging = status == 2 || source != null,
            powerSource = source,
            temperatureC = kv["temperature"]?.toIntOrNull()?.let { it / 10.0 },
        )
    }

    fun parseMeminfo(out: String): MemInfo? {
        fun kb(key: String) = Regex("""^$key:\s+(\d+)\s*kB""", RegexOption.MULTILINE)
            .find(out)?.groupValues?.get(1)?.toLongOrNull()
        val total = kb("MemTotal") ?: return null
        val avail = kb("MemAvailable") ?: kb("MemFree") ?: return null
        return MemInfo(total, avail)
    }

    fun parseDf(out: String): StorageInfo? {
        val row = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .lastOrNull { !it.startsWith("Filesystem") } ?: return null
        val t = row.split(Regex("\\s+"))
        if (t.size < 4) return null
        val total = t[1].toLongOrNull() ?: return null
        val used = t[2].toLongOrNull() ?: return null
        val avail = t[3].toLongOrNull() ?: return null
        return StorageInfo(total, used, avail)
    }

    fun parseWifiStatus(out: String): String? {
        if (out.contains("Unknown command", true) || out.contains("Exception", true)) return null
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val relevant = lines.filter { it.startsWith("Wifi is", ignoreCase = true) }
        return (relevant.ifEmpty { lines.take(1) }).joinToString(" · ").ifBlank { null }
    }

    fun parsePackageList(out: String): Set<String> =
        out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()

    fun parsePackageDump(pkg: String, out: String): PackageDetails {
        val all = out.lines()
        // Narrow to this package's block under "Packages:" when present.
        val start = all.indexOfFirst { it.trim().startsWith("Package [$pkg]") }
        val block = if (start >= 0) {
            val indent = indentOf(all[start])
            val end = (start + 1 until all.size).firstOrNull { i ->
                all[i].isNotBlank() && indentOf(all[i]) <= indent
            } ?: all.size
            all.subList(start, end)
        } else all
        val text = block.joinToString("\n")

        fun find(regex: String) = Regex(regex).find(text)?.groupValues?.get(1)?.trim()

        val flags = find("""pkgFlags=\[([^\]]*)]""")?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        val sharedUser = find("""sharedUser=SharedUserSetting\{\S+ ([^/}]+)/""")

        val libs = (collectIndented(block, "usesLibraries:") +
            collectIndented(block, "usesOptionalLibraries:") +
            collectIndented(block, "usesStaticLibraries:")).distinct()

        val perms = collectIndented(block, "requested permissions:").map { it.substringBefore(':').trim() }

        return PackageDetails(
            packageName = pkg,
            versionName = find("""versionName=(\S+)"""),
            versionCode = find("""versionCode=(\d+)"""),
            minSdk = find("""minSdk=(\d+)"""),
            targetSdk = find("""targetSdk=(\d+)"""),
            firstInstallTime = find("""firstInstallTime=([^\n]+)"""),
            lastUpdateTime = find("""lastUpdateTime=([^\n]+)"""),
            installer = find("""installerPackageName=(\S+)"""),
            codePath = find("""codePath=(\S+)"""),
            flags = flags,
            sharedUserId = sharedUser,
            libraries = libs,
            requestedPermissions = perms,
            user0EnabledState = Regex("""User 0:[^\n]*?\benabled=(\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull(),
            user0Installed = Regex("""User 0:[^\n]*?\binstalled=(true|false)""").find(text)?.groupValues?.get(1)?.toBoolean(),
        )
    }

    private fun indentOf(s: String) = s.length - s.trimStart().length

    private fun collectIndented(lines: List<String>, header: String): List<String> {
        val idx = lines.indexOfFirst { it.trim() == header }
        if (idx < 0) return emptyList()
        val base = indentOf(lines[idx])
        val out = mutableListOf<String>()
        for (i in idx + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank()) break
            if (indentOf(l) <= base) break
            out += l.trim()
        }
        return out
    }

    /** Samsung reports e.g. 50001 → "5.0.1". Returned with the raw value for transparency. */
    fun formatOneUi(raw: String?): String? {
        val v = raw?.toIntOrNull() ?: return raw?.ifBlank { null }
        if (v < 10000) return raw
        return "${v / 10000}.${(v / 100) % 100}.${v % 100} (raw $raw)"
    }

    /** Wear OS version is not exposed as a stable property; estimate from the API level. */
    fun estimateWearOs(sdk: Int?): String? = when (sdk) {
        null -> null
        30 -> "Wear OS 3"
        33 -> "Wear OS 4"
        34 -> "Wear OS 5"
        35 -> "Wear OS 5.1"
        36 -> "Wear OS 6"
        else -> null
    }

    /** Package labels aren't available over plain ADB (no aapt on the watch). */
    fun derivedName(pkg: String): String {
        val skip = setOf("com", "android", "google", "samsung", "sec", "app", "apps", "wearable", "watch", "org", "net")
        val seg = pkg.split('.').lastOrNull { it.lowercase() !in skip } ?: pkg.substringAfterLast('.')
        return seg.replace('_', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { it.uppercase() }
    }
}
