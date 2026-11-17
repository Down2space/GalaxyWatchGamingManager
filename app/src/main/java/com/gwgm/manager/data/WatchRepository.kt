package com.gwgm.manager.data

import com.gwgm.manager.adb.WatchConnection
import com.gwgm.manager.safety.AppCategory
import com.gwgm.manager.safety.Classification
import com.gwgm.manager.safety.ProtectionRules
import kotlinx.coroutines.CancellationException

/** Read-only queries against the watch. Nothing here can change watch state. */
class WatchRepository(
    private val conn: WatchConnection,
    private val rules: ProtectionRules,
) {

    suspend fun fetchWatchInfo(): WatchInfo {
        val errors = mutableListOf<String>()
        suspend fun <T> step(label: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "$label: ${e.message ?: e.javaClass.simpleName}"
            null
        }

        val props = step("System properties") { WatchParsers.parseGetprop(conn.exec("getprop")) }.orEmpty()
        val sdk = props["ro.build.version.sdk"]?.toIntOrNull()
        val battery = step("Battery") { WatchParsers.parseBattery(conn.exec("dumpsys battery")) }
        val mem = step("Memory") { WatchParsers.parseMeminfo(conn.exec("cat /proc/meminfo")) }
        val storage = step("Storage") { WatchParsers.parseDf(conn.exec("df -k /data")) }
        val wifi = step("Wi-Fi") { WatchParsers.parseWifiStatus(conn.exec("cmd wifi status")) }
        val count = step("Apps") { WatchParsers.parsePackageList(conn.exec("pm list packages")).size }

        return WatchInfo(
            model = props["ro.product.model"],
            marketName = props["ro.product.vendor.marketname"] ?: props["ro.product.marketname"],
            androidRelease = props["ro.build.version.release"],
            sdk = sdk,
            wearOsEstimate = WatchParsers.estimateWearOs(sdk),
            oneUiWatch = WatchParsers.formatOneUi(props["ro.build.version.oneui"]),
            buildId = props["ro.build.display.id"],
            battery = battery,
            memory = mem,
            storage = storage,
            wifiStatus = wifi,
            appCount = count,
            errors = errors,
        )
    }

    suspend fun fetchInventory(): List<AppEntry> {
        val installed = WatchParsers.parsePackageList(conn.exec("pm list packages"))
        val all = WatchParsers.parsePackageList(conn.exec("pm list packages -u")) + installed
        val system = WatchParsers.parsePackageList(conn.exec("pm list packages -s"))
        val disabled = WatchParsers.parsePackageList(conn.exec("pm list packages -d"))
        val third = WatchParsers.parsePackageList(conn.exec("pm list packages -3"))

        return all.map { pkg ->
            // Packages removed-for-user (e.g. by an earlier debloat) show in -u only;
            // anything not explicitly third-party is treated as system.
            val isSystem = pkg in system || pkg !in third
            AppEntry(
                packageName = pkg,
                displayName = WatchParsers.derivedName(pkg),
                nameIsDerived = true,
                isSystem = isSystem,
                isEnabled = pkg !in disabled,
                installedForUser = pkg in installed,
                classification = rules.classify(pkg, isSystem),
            )
        }.sortedWith(compareBy<AppEntry> { it.classification.category.ordinal }.thenBy { it.packageName })
    }

    /** Details + re-classification using the shared UID that only dumpsys reveals. */
    suspend fun fetchDetails(entry: AppEntry): Pair<PackageDetails, Classification> {
        val d = WatchParsers.parsePackageDump(entry.packageName, conn.exec("dumpsys package ${entry.packageName}"))
        val c = rules.classify(entry.packageName, entry.isSystem, d.sharedUserId)
        return d to c
    }

    val categoriesInOrder = AppCategory.entries
}
