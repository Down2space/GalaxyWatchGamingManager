package com.gwgm.manager.data

import com.gwgm.manager.safety.Classification

data class BatteryInfo(
    val levelPercent: Int?,
    val charging: Boolean,
    val powerSource: String?,
    val temperatureC: Double?,
)

data class MemInfo(val totalKb: Long, val availableKb: Long)

data class StorageInfo(val totalKb: Long, val usedKb: Long, val availableKb: Long)

data class WatchInfo(
    val model: String?,
    val marketName: String?,
    val androidRelease: String?,
    val sdk: Int?,
    val wearOsEstimate: String?,
    val oneUiWatch: String?,
    val buildId: String?,
    val battery: BatteryInfo?,
    val memory: MemInfo?,
    val storage: StorageInfo?,
    val wifiStatus: String?,
    val appCount: Int?,
    val errors: List<String>,
)

data class AppEntry(
    val packageName: String,
    val displayName: String,
    val nameIsDerived: Boolean,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val installedForUser: Boolean,
    val classification: Classification,
)

data class PackageDetails(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val firstInstallTime: String?,
    val lastUpdateTime: String?,
    val installer: String?,
    val codePath: String?,
    val flags: List<String>,
    val sharedUserId: String?,
    val libraries: List<String>,
    val requestedPermissions: List<String>,
    /** 0 default, 1 enabled, 2 disabled (system), 3 disabled-user, 4 disabled-until-used. */
    val user0EnabledState: Int? = null,
    val user0Installed: Boolean? = null,
) {
    /** Only dependencies that dumpsys states explicitly — nothing guessed. */
    val knownDependencies: List<String>
        get() = buildList {
            libraries.forEach { add("Library: $it") }
            sharedUserId?.let { add("Shares process identity with: $it") }
        }
}
