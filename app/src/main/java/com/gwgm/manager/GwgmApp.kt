package com.gwgm.manager

import android.app.Application
import android.content.Context
import com.gwgm.manager.adb.AdbDiscovery
import com.gwgm.manager.adb.WatchConnection
import com.gwgm.manager.data.WatchRepository
import com.gwgm.manager.safety.ChangeJournal
import com.gwgm.manager.safety.ChangePlanner
import com.gwgm.manager.safety.OptionalAllowlist
import com.gwgm.manager.safety.ProtectionRules
import org.conscrypt.Conscrypt
import java.security.Security

class GwgmApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // libadb's TLS pairing needs the *bundled* Conscrypt (the platform copy is repackaged
        // and its keying-material export API isn't reachable from apps).
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
    }
}

/** Manual dependency wiring — small enough that a DI framework isn't worth it. */
class AppContainer(context: Context) {
    val connection = WatchConnection(context)
    val discovery = AdbDiscovery(context)
    val rules = ProtectionRules(OptionalAllowlist.load(context))
    val repository = WatchRepository(connection, rules)
    val journal = ChangeJournal(context)
    val planner = ChangePlanner(connection, repository, journal)
    val prefs = context.getSharedPreferences("gwgm_modes", Context.MODE_PRIVATE)
}
