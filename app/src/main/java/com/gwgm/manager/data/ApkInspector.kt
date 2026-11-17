package com.gwgm.manager.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

data class ApkInfo(
    val file: File,
    val packageName: String,
    val label: String,
    val versionName: String?,
    val minSdk: Int,
    /** Declares <uses-feature android:name="android.hardware.type.watch">. */
    val isWatchApp: Boolean,
    /** Wear OS standalone flag, if declared. */
    val standalone: Boolean?,
    /** Native code folders in the APK (empty = pure Java/Kotlin, runs on any CPU). */
    val abis: List<String>,
    val sizeBytes: Long,
)

/** Reads an APK on the PHONE before anything is sent to the watch. */
object ApkInspector {

    val WATCH_ABIS = setOf("arm64-v8a", "armeabi-v7a", "armeabi")

    fun copyToCache(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "installs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "pending.apk")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Couldn't open that file.")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        return f
    }

    fun inspect(context: Context, f: File): ApkInfo {
        val pm = context.packageManager
        val flags = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA
        val pi: PackageInfo = (if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(f.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(f.path, flags)
        }) ?: throw IllegalArgumentException(
            "That isn't a single installable APK. Bundles (.apks, .xapk, .apkm, .aab) and zip files aren't supported yet — pick a plain .apk."
        )
        val ai = pi.applicationInfo ?: throw IllegalArgumentException("The APK has no application info.")
        ai.sourceDir = f.path
        ai.publicSourceDir = f.path
        val abis = runCatching {
            ZipFile(f).use { z ->
                z.entries().asSequence().map { it.name }
                    .filter { it.startsWith("lib/") && it.count { c -> c == '/' } >= 2 }
                    .map { it.split('/')[1] }.distinct().toList()
            }
        }.getOrDefault(emptyList())
        val meta = ai.metaData
        return ApkInfo(
            file = f,
            packageName = pi.packageName,
            label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pi.packageName),
            versionName = pi.versionName,
            minSdk = ai.minSdkVersion,
            isWatchApp = pi.reqFeatures?.any { it.name == "android.hardware.type.watch" } == true,
            standalone = if (meta != null && meta.containsKey("com.google.android.wearable.standalone"))
                meta.getBoolean("com.google.android.wearable.standalone") else null,
            abis = abis,
            sizeBytes = f.length(),
        )
    }
}
