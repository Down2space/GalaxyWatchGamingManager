package com.gwgm.manager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gwgm.manager.GwgmApp
import com.gwgm.manager.adb.AdbDiscovery
import com.gwgm.manager.adb.ConnState
import com.gwgm.manager.adb.Endpoint
import com.gwgm.manager.data.AppEntry
import com.gwgm.manager.data.PackageDetails
import com.gwgm.manager.data.WatchInfo
import com.gwgm.manager.safety.ChangeResult
import com.gwgm.manager.safety.Classification
import com.gwgm.manager.safety.JournalEntry
import com.gwgm.manager.safety.ProposedChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val entry: AppEntry,
    val loading: Boolean = true,
    val details: PackageDetails? = null,
    val classification: Classification = entry.classification,
    val error: String? = null,
)

enum class OpMode(val title: String, val blurb: String) {
    STANDARD("Standard", "Original smartwatch experience. Undoes every change this app made."),
    GAMING("Gaming", "Faster UI animations, clears cached background apps, opens the game hub. All reversible."),
    CUSTOM("Custom", "Disable individually reviewed user apps or verified-optional packages, one at a time."),
}

/** A change set waiting for (or going through) explicit confirmation. */
data class ReviewState(
    val heading: String,
    val changes: List<ProposedChange>,
    val modeAfter: OpMode?,
    val acknowledged: Boolean = false,
    val running: Boolean = false,
    val results: List<ChangeResult> = emptyList(),
    val done: Boolean = false,
)

data class UiState(
    val conn: ConnState = ConnState.Disconnected,
    val host: String = "",
    val connectPort: String = "",
    val hasIdentity: Boolean = false,
    val connectExplained: Boolean = false,
    val info: WatchInfo? = null,
    val infoLoading: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
    val appsLoading: Boolean = false,
    val appsError: String? = null,
    val detail: DetailState? = null,
    val pairingFound: List<Endpoint> = emptyList(),
    val connectFound: List<Endpoint> = emptyList(),
    val discovering: Boolean = false,
    val message: String? = null,
    val mode: OpMode = OpMode.STANDARD,
    val journal: List<JournalEntry> = emptyList(),
    val planning: Boolean = false,
    val review: ReviewState? = null,
    /** Set after any successful change: the calling checklist should be run again. */
    val checklistDue: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val c = (app as GwgmApp).container
    private val _ui = MutableStateFlow(
        UiState(
            host = c.connection.lastHost.orEmpty(),
            connectPort = c.connection.lastPort?.toString().orEmpty(),
            hasIdentity = c.connection.hasIdentity,
            mode = runCatching { OpMode.valueOf(c.prefs.getString("mode", "STANDARD")!!) }.getOrDefault(OpMode.STANDARD),
            journal = c.journal.all().sortedByDescending { it.time },
            checklistDue = c.prefs.getBoolean("checklist_due", false),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { c.connection.state.collect { s -> _ui.update { it.copy(conn = s) } } }
    }

    fun setHost(v: String) = _ui.update { it.copy(host = v.trim()) }
    fun setConnectPort(v: String) = _ui.update { it.copy(connectPort = v.filter(Char::isDigit).take(5)) }
    fun markConnectExplained() = _ui.update { it.copy(connectExplained = true) }
    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun pair(host: String, port: String, code: String) {
        val p = port.toIntOrNull() ?: return say("Enter the pairing port shown on the watch.")
        viewModelScope.launch {
            c.connection.pair(host.trim(), p, code.trim())
                .onSuccess {
                    _ui.update { it.copy(host = host.trim(), hasIdentity = true) }
                    say("Paired! Now enter the Wireless debugging port (not the pairing port) and tap Connect.")
                }
                .onFailure { say(it.message) }
        }
    }

    fun connect() {
        val s = _ui.value
        val p = s.connectPort.toIntOrNull() ?: return say("Enter the connection port from the watch's Wireless debugging screen.")
        if (s.host.isBlank()) return say("Enter the watch's IP address.")
        viewModelScope.launch {
            c.connection.connect(s.host, p).onSuccess { refresh() }
        }
    }

    fun disconnect() { viewModelScope.launch {
        c.connection.disconnect()
        _ui.update { it.copy(info = null) }
    } }

    fun forgetIdentity() { viewModelScope.launch {
        c.connection.forgetIdentity()
        _ui.update { it.copy(hasIdentity = false, info = null, apps = emptyList()) }
        say("This phone's ADB identity was deleted. Also remove it on the watch: Wireless debugging › Paired devices.")
    } }

    fun refresh() {
        if (!c.connection.isConnected) return say("Connect to the watch first.")
        viewModelScope.launch {
            _ui.update { it.copy(infoLoading = true) }
            val info = runCatching { c.repository.fetchWatchInfo() }
            _ui.update { it.copy(infoLoading = false, info = info.getOrNull() ?: it.info) }
            info.exceptionOrNull()?.let { if (it !is CancellationException) say(it.message) }
        }
    }

    fun loadInventory() {
        if (!c.connection.isConnected) return say("Connect to the watch first.")
        viewModelScope.launch {
            _ui.update { it.copy(appsLoading = true, appsError = null) }
            runCatching { c.repository.fetchInventory() }
                .onSuccess { list -> _ui.update { it.copy(appsLoading = false, apps = list) } }
                .onFailure { e -> _ui.update { it.copy(appsLoading = false, appsError = e.message) } }
        }
    }

    fun openDetail(entry: AppEntry) {
        _ui.update { it.copy(detail = DetailState(entry)) }
        viewModelScope.launch {
            runCatching { c.repository.fetchDetails(entry) }
                .onSuccess { (d, cl) ->
                    _ui.update { s -> s.copy(detail = s.detail?.copy(loading = false, details = d, classification = cl)) }
                }
                .onFailure { e ->
                    _ui.update { s -> s.copy(detail = s.detail?.copy(loading = false, error = e.message)) }
                }
        }
    }

    fun closeDetail() = _ui.update { it.copy(detail = null) }

    fun discover(pairing: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(discovering = true) }
            val type = if (pairing) AdbDiscovery.PAIRING_SERVICE else AdbDiscovery.CONNECT_SERVICE
            val found = runCatching { c.discovery.scan(type) }.getOrDefault(emptyList())
            _ui.update {
                if (pairing) it.copy(discovering = false, pairingFound = found)
                else it.copy(discovering = false, connectFound = found)
            }
            if (found.isEmpty()) say("Nothing found. Make sure the watch's Wireless debugging screen is open and both devices are on the same Wi-Fi. You can type the values instead.")
        }
    }

    fun useConnectEndpoint(e: Endpoint) = _ui.update { it.copy(host = e.host, connectPort = e.port.toString()) }

    // ---- Phase 3/4: reviewed, reversible changes ----------------------------------------------

    fun proposeDisable(entry: AppEntry) = plan("Disable ${entry.displayName}", OpMode.CUSTOM) { listOf(c.planner.planDisable(entry.packageName)) }
    fun proposeEnable(entry: AppEntry) = plan("Re-enable ${entry.displayName}", null) { listOf(c.planner.planEnable(entry.packageName)) }
    fun proposeReinstall(entry: AppEntry) = plan("Reinstall ${entry.displayName}", null) { listOf(c.planner.planInstallExisting(entry.packageName)) }
    fun proposeGamingMode() = plan("Switch to Gaming Mode", OpMode.GAMING) { c.planner.planGamingMode() }
    fun proposeStandardMode() = plan("Switch to Standard Mode", OpMode.STANDARD) { c.planner.planStandardMode() }
    fun proposeRestore(e: JournalEntry) = plan("Undo one change", null) { listOf(c.planner.planRestore(e)) }

    private fun plan(heading: String, modeAfter: OpMode?, block: suspend () -> List<ProposedChange>) {
        if (!c.connection.isConnected) return say("Connect to the watch first.")
        viewModelScope.launch {
            _ui.update { it.copy(planning = true) }
            runCatching { block() }
                .onSuccess { changes ->
                    if (changes.isEmpty()) {
                        modeAfter?.let { setMode(it) }
                        say(if (modeAfter == OpMode.STANDARD) "Nothing to undo — the watch is already in its original state." else "Nothing needs changing.")
                        _ui.update { it.copy(planning = false) }
                    } else _ui.update { it.copy(planning = false, review = ReviewState(heading, changes, modeAfter)) }
                }
                .onFailure { e -> _ui.update { it.copy(planning = false) }; say(e.message) }
        }
    }

    fun setAcknowledged(v: Boolean) = _ui.update { s -> s.copy(review = s.review?.copy(acknowledged = v)) }

    /** Step 6 → 7: user confirmed. Runs each approved change in order; stops at the first failure. */
    fun confirmReview() {
        val r = _ui.value.review ?: return
        if (!r.acknowledged || r.running || r.done) return
        viewModelScope.launch {
            _ui.update { it.copy(review = r.copy(running = true)) }
            val results = mutableListOf<ChangeResult>()
            for (change in r.changes) {
                val res = runCatching { c.planner.execute(change) }
                    .getOrElse { e -> ChangeResult(change, false, e.message ?: "Failed") }
                results += res
                _ui.update { s -> s.copy(review = s.review?.copy(results = results.toList())) }
                if (!res.success) break
            }
            val allOk = results.size == r.changes.size && results.all { it.success }
            if (allOk) r.modeAfter?.let { setMode(it) }
            val changedSomething = results.any { it.success }
            if (changedSomething) c.prefs.edit().putBoolean("checklist_due", true).apply()
            _ui.update { s ->
                s.copy(
                    review = s.review?.copy(running = false, done = true),
                    journal = c.journal.all().sortedByDescending { it.time },
                    checklistDue = s.checklistDue || changedSomething,
                )
            }
            if (changedSomething) loadInventory()
        }
    }

    fun dismissReview() {
        if (_ui.value.review?.running == true) return
        _ui.update { it.copy(review = null) }
    }

    fun checklistCompleted() {
        c.prefs.edit().putBoolean("checklist_due", false).apply()
        _ui.update { it.copy(checklistDue = false) }
    }

    fun manualRestoreScript(): String = c.journal.manualRestoreScript()

    private fun setMode(m: OpMode) {
        c.prefs.edit().putString("mode", m.name).apply()
        _ui.update { it.copy(mode = m) }
    }

    private fun say(msg: String?) = _ui.update { it.copy(message = msg ?: "Something went wrong.") }
}
