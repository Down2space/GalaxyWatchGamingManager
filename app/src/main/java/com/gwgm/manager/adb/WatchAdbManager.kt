package com.gwgm.manager.adb

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * libadb-android connection manager bound to this phone's stored identity.
 * Provides: pair(host, port, code), connect(host, port), openStream("shell:..."), disconnect().
 */
class WatchAdbManager(private val credentials: AdbCredentialStore.Credentials) : AbsAdbConnectionManager() {

    init {
        // API level of the *watch's* adbd. Galaxy Watch6 runs Wear OS 4/5 (Android 13/14),
        // both of which use TLS wireless debugging. Anything >= R selects the TLS code path.
        setApi(Build.VERSION_CODES.TIRAMISU)
    }

    override fun getPrivateKey(): PrivateKey = credentials.privateKey

    override fun getCertificate(): Certificate = credentials.certificate

    /** Shown on the watch under Wireless debugging › Paired devices. */
    override fun getDeviceName(): String = "Watch Gaming Manager (${Build.MODEL})"
}
