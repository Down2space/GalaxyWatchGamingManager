package com.gwgm.manager

import com.gwgm.manager.data.WatchParsers
import com.gwgm.manager.safety.AppCategory
import com.gwgm.manager.safety.ApprovedCommand
import com.gwgm.manager.safety.OptionalEntry
import com.gwgm.manager.safety.ProtectionRules
import com.gwgm.manager.safety.ReadOnlyCommandPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersAndRulesTest {

    @Test fun getprop() {
        val m = WatchParsers.parseGetprop("[ro.product.model]: [SM-R965U]\n[ro.build.version.sdk]: [34]\n")
        assertEquals("SM-R965U", m["ro.product.model"])
        assertEquals("34", m["ro.build.version.sdk"])
    }

    @Test fun battery() {
        val b = WatchParsers.parseBattery(
            "Current Battery Service state:\n  AC powered: false\n  USB powered: false\n  Wireless powered: true\n" +
                "  status: 2\n  level: 76\n  scale: 100\n  temperature: 312\n"
        )
        assertEquals(76, b.levelPercent)
        assertTrue(b.charging)
        assertEquals("Wireless", b.powerSource)
        assertEquals(31.2, b.temperatureC!!, 0.01)
    }

    @Test fun meminfoAndDf() {
        val m = WatchParsers.parseMeminfo("MemTotal:        1918028 kB\nMemFree:  10 kB\nMemAvailable:     812345 kB\n")!!
        assertEquals(1918028, m.totalKb); assertEquals(812345, m.availableKb)
        val s = WatchParsers.parseDf("Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-40 25000000 9000000 16000000  36% /data\n")!!
        assertEquals(16000000, s.availableKb)
    }

    @Test fun wifi() {
        assertEquals("Wifi is enabled · Wifi is connected to \"Home\"",
            WatchParsers.parseWifiStatus("Wifi is enabled\nWifi scanning is always available\nWifi is connected to \"Home\"\nWifiInfo: ...\n"))
    }

    @Test fun packageList() {
        assertEquals(setOf("android", "com.a.b"), WatchParsers.parsePackageList("package:android\npackage:com.a.b\n\n"))
    }

    @Test fun packageDump() {
        val dump = """
            Packages:
              Package [com.example.game] (abc123):
                userId=10123
                sharedUser=SharedUserSetting{deadbeef android.uid.system/1000}
                codePath=/data/app/~~x/com.example.game-1
                versionCode=42 minSdk=30 targetSdk=33
                versionName=1.2.3
                pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
                usesLibraries:
                  android.test.base
                requested permissions:
                  android.permission.INTERNET
                  android.permission.RECORD_AUDIO
                firstInstallTime=2026-01-01 10:00:00
                lastUpdateTime=2026-02-01 10:00:00
                installerPackageName=com.android.vending
                User 0: ceDataInode=123 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=3 instant=false
        """.trimIndent()
        val d = WatchParsers.parsePackageDump("com.example.game", dump)
        assertEquals("1.2.3", d.versionName)
        assertEquals("42", d.versionCode)
        assertEquals("android.uid.system", d.sharedUserId)
        assertEquals(listOf("android.test.base"), d.libraries)
        assertEquals(2, d.requestedPermissions.size)
        assertEquals(listOf("HAS_CODE", "ALLOW_CLEAR_USER_DATA"), d.flags)
        assertEquals(3, d.user0EnabledState)
        assertEquals(true, d.user0Installed)
    }

    @Test fun oneUiAndWear() {
        assertEquals("5.0.1 (raw 50001)", WatchParsers.formatOneUi("50001"))
        assertEquals("Wear OS 5", WatchParsers.estimateWearOs(34))
    }

    private val rules = ProtectionRules(mapOf("com.samsung.android.optionaldemo" to
        OptionalEntry("com.samsung.android.optionaldemo", "demo", "SM-R965U test")))

    @Test fun criticalPackagesAreProtected() {
        listOf(
            "android", "com.android.phone", "com.android.server.telecom", "com.android.bluetooth",
            "com.samsung.android.wearable.watchmanager", "com.android.providers.telephony",
            "com.samsung.android.app.euicc", "com.samsung.accessory", "com.android.systemui",
            "com.google.android.gms", "com.android.shell", "com.samsung.android.audio.ims",
        ).forEach { assertEquals(it, AppCategory.PROTECTED, rules.classify(it, isSystem = true).category) }
    }

    @Test fun carrierCompanionsProtectedEvenAsUserApps() {
        listOf("com.verizon.numbersync", "com.tmobile.digits", "com.att.callplus", "com.example.bluetoothremote")
            .forEach { assertEquals(it, AppCategory.PROTECTED, rules.classify(it, isSystem = false).category) }
    }

    @Test fun sharedUidUpgradesToProtected() {
        assertEquals(AppCategory.PROTECTED, rules.classify("com.vendor.mystery", true, "android.uid.phone").category)
    }

    @Test fun unknownSystemIsNeverAssumedSafe() {
        val c = rules.classify("com.vendor.mystery", isSystem = true)
        assertEquals(AppCategory.UNKNOWN, c.category); assertFalse(c.modifiable)
    }

    @Test fun shortKeywordsMatchWholeSegmentsOnly() {
        // "sim" must not match inside "simple"
        assertEquals(AppCategory.UNKNOWN, rules.classify("com.vendor.simplegame", isSystem = true).category)
    }

    @Test fun allowlistAndUserApps() {
        assertEquals(AppCategory.OPTIONAL_SAMSUNG, rules.classify("com.samsung.android.optionaldemo", true).category)
        assertEquals(AppCategory.USER, rules.classify("com.example.snake", isSystem = false).category)
    }

    @Test fun modificationTemplates() {
        listOf(
            "pm disable-user --user 0 com.example.snake", "pm enable --user 0 com.example.snake",
            "pm install-existing --user 0 com.samsung.android.app.x", "settings put global window_animation_scale 0.5",
            "settings put global animator_duration_scale 1.0", "settings delete global transition_animation_scale",
            "am kill-all", "am start -n com.gwgm.watch/.MainActivity",
        ).forEach { assertTrue(it, ApprovedCommand.isAllowed(it)) }
        listOf(
            "pm uninstall --user 0 com.foo", "pm clear com.foo", "reboot", "pm disable-user com.foo",
            "settings put global airplane_mode_on 1", "settings put global window_animation_scale 50",
            "settings put secure bluetooth_on 0", "svc wifi disable", "pm disable-user --user 0 com.foo; reboot",
            "am start -n com.evil/.Main -d tel:911", "pm enable --user 0 com.foo && reboot",
        ).forEach { assertFalse(it, ApprovedCommand.isAllowed(it)) }
        assertNotNull(runCatching { ApprovedCommand.approve("reboot") }.exceptionOrNull())
    }

    @Test fun installAndLaunchTemplates() {
        assertTrue(ApprovedCommand.isAllowed("cmd package install -r -t -S 12345678"))
        assertTrue(ApprovedCommand.isAllowed("am start -n com.example.snake/.MainActivity"))
        assertTrue(ApprovedCommand.isAllowed("am start -n com.example.snake/com.example.snake.ui.GameActivity"))
        assertTrue(ApprovedCommand.isAllowed("pm uninstall com.example.snake"))
        assertFalse(ApprovedCommand.isAllowed("cmd package install -S 0"))
        assertFalse(ApprovedCommand.isAllowed("cmd package install -r -t -S 5 /sdcard/x.apk"))
        assertFalse(ApprovedCommand.isAllowed("pm uninstall -k --user 0 com.foo"))
        assertFalse(ApprovedCommand.isAllowed("am start -a android.intent.action.CALL -d tel:911"))
        assertTrue(ReadOnlyCommandPolicy.isAllowed("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.example.snake"))
    }

    @Test fun readOnlyPolicyBlocksModifying() {
        assertFalse(ReadOnlyCommandPolicy.isAllowed("pm disable-user --user 0 com.foo"))
        assertTrue(ReadOnlyCommandPolicy.isAllowed("settings get global window_animation_scale"))
        assertFalse(ReadOnlyCommandPolicy.isAllowed("settings get secure android_id"))
    }

    @Test fun readOnlyPolicy() {
        assertTrue(ReadOnlyCommandPolicy.isAllowed("pm list packages -s"))
        assertTrue(ReadOnlyCommandPolicy.isAllowed("dumpsys package com.android.phone"))
        assertFalse(ReadOnlyCommandPolicy.isAllowed("pm disable-user com.foo"))
        assertFalse(ReadOnlyCommandPolicy.isAllowed("pm uninstall -k --user 0 com.foo"))
        assertFalse(ReadOnlyCommandPolicy.isAllowed("getprop; reboot"))
        assertFalse(ReadOnlyCommandPolicy.isAllowed("dumpsys package com.foo && reboot"))
        assertNotNull(runCatching { ReadOnlyCommandPolicy.requireAllowed("reboot") }.exceptionOrNull())
    }
}
