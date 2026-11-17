package com.gwgm.manager.adb

import android.content.Context
import com.gwgm.manager.safety.ApprovedCommand
import com.gwgm.manager.safety.ReadOnlyCommandPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

sealed interface ConnState {
    data object Disconnected : ConnState
    data class Busy(val what: String) : ConnState
    data class Connected(val host: String, val port: Int) : ConnState
    data class Failed(val message: String) : ConnState
}

/**
 * Owns the single ADB link to the watch: pairing, connecting, disconnecting, running
 * (read-only, policy-checked) shell commands, and one-shot automatic reconnection.
 */
class WatchConnection(context: Context) {

    private val credentialStore = AdbCredentialStore(context.applicationContext)
    private val prefs = context.applicationContext.getSharedPreferences("gwgm_connection", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "gwgm-adb").apply { isDaemon = true } }

    @Volatile private var manager: WatchAdbManager? = null

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    val lastHost: String? get() = prefs.getString(KEY_HOST, null)
    val lastPort: Int? get() = prefs.getInt(KEY_PORT, -1).takeIf { it > 0 }
    val hasIdentity: Boolean get() = credentialStore.hasCredentials()
    val isConnected: Boolean get() = _state.value is ConnState.Connected

    suspend fun pair(host: String, port: Int, code: String): Result<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                require(code.matches(Regex("\\d{6}"))) { "The pairing code must be exactly 6 digits." }
                _state.value = ConnState.Busy("Pairing with $host:$port…")
                val ok = callWithTimeout(PAIR_TIMEOUT_MS) { managerLocked().pair(host, port, code) }
                if (!ok) throw IOException("The watch rejected the pairing request.")
                prefs.edit().putString(KEY_HOST, host).apply()
                // Pairing does not connect; the connect port is different.
                _state.value = ConnState.Disconnected
            }.onFailure { e ->
                resetManagerLocked()
                _state.value = ConnState.Failed(friendlyError(e, pairing = true))
            }
        }
    }

    suspend fun connect(host: String, port: Int): Result<Unit> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                _state.value = ConnState.Busy("Connecting to $host:$port…")
                resetManagerLocked()
                val ok = callWithTimeout(CONNECT_TIMEOUT_MS) { managerLocked().connect(host, port) }
                if (!ok) throw IOException("The watch did not accept the connection.")
                prefs.edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply()
                _state.value = ConnState.Connected(host, port)
                // Prove the link actually works end-to-end.
                val ping = execOnce("echo gwgm_ping", 8_000)
                if (!ping.contains("gwgm_ping")) throw IOException("Connected, but the shell did not respond.")
            }.onFailure { e ->
                resetManagerLocked()
                _state.value = ConnState.Failed(friendlyError(e, pairing = false))
            }
        }
    }

    suspend fun disconnect() = lock.withLock {
        withContext(Dispatchers.IO) {
            resetManagerLocked()
            _state.value = ConnState.Disconnected
        }
    }

    /** Deletes this phone's ADB identity. The watch must be paired again afterwards. */
    suspend fun forgetIdentity() = lock.withLock {
        withContext(Dispatchers.IO) {
            resetManagerLocked()
            credentialStore.delete()
            prefs.edit().clear().apply()
            _state.value = ConnState.Disconnected
        }
    }

    /**
     * Runs a shell command on the watch. Every command is checked against [ReadOnlyCommandPolicy]
     * first — this build physically cannot send a modifying command.
     * On a dropped link it makes ONE reconnect attempt to the last endpoint, then retries once.
     */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): String {
        ReadOnlyCommandPolicy.requireAllowed(command)
        return withContext(Dispatchers.IO) {
            try {
                execOnce(command, timeoutMs)
            } catch (e: IOException) {
                val host = lastHost
                val port = lastPort
                if (host == null || port == null || _state.value !is ConnState.Connected) {
                    markLost(e); throw e
                }
                lock.withLock {
                    _state.value = ConnState.Busy("Connection dropped — reconnecting…")
                    resetManagerLocked()
                    val ok = runCatching {
                        callWithTimeout(CONNECT_TIMEOUT_MS) { managerLocked().connect(host, port) }
                    }.getOrDefault(false)
                    if (!ok) {
                        resetManagerLocked()
                        markLost(e)
                        throw IOException(
                            "Lost the connection and couldn't reconnect automatically. " +
                                "Check the watch's Wi-Fi and the current Wireless debugging port, then tap Connect.", e
                        )
                    }
                    _state.value = ConnState.Connected(host, port)
                }
                execOnce(command, timeoutMs)
            }
        }
    }

    /**
     * The ONLY way to send a state-changing command. Takes an [ApprovedCommand] (which can only be
     * built from the fixed reversible templates) and never auto-retries: a modifying command must
     * not run twice by accident.
     */
    suspend fun execModifying(command: ApprovedCommand, timeoutMs: Long = 20_000): String =
        withContext(Dispatchers.IO) {
            if (_state.value !is ConnState.Connected) throw IOException("Not connected to the watch.")
            try {
                execOnce(command.text, timeoutMs)
            } catch (e: IOException) {
                markLost(e)
                throw IOException("Connection dropped while running the change. Reconnect and check the journal/state before retrying.", e)
            }
        }

    // ---------------------------------------------------------------------------------------

    private fun execOnce(command: String, timeoutMs: Long): String {
        val m = manager?.takeIf { it.isConnected } ?: throw IOException("Not connected to the watch.")
        val stream = m.openStream("shell:$command")
        try {
            return callWithTimeout(timeoutMs) {
                // libadb throws IOException("Stream closed.") (from read() and available()) when the
                // watch closes the stream after a command finishes, instead of returning -1. Read raw
                // bytes ourselves and treat a close by the watch as normal end-of-output.
                val input = stream.openInputStream()
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = try {
                        input.read(buf, 0, buf.size)
                    } catch (e: IOException) {
                        if (stream.isClosed) -1 else throw e
                    }
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }.replace("\r", "")
        } finally {
            // Also unblocks the reader thread if we timed out.
            runCatching { stream.close() }
        }
    }

    private fun managerLocked(): WatchAdbManager =
        manager ?: WatchAdbManager(credentialStore.loadOrCreate()).also { manager = it }

    private fun resetManagerLocked() {
        val old = manager ?: return
        manager = null
        io.execute { runCatching { old.disconnect() } }
    }

    private fun markLost(e: Throwable) {
        _state.value = ConnState.Failed(friendlyError(e, pairing = false))
    }

    /** Runs a blocking libadb call with a hard timeout (socket calls aren't interruptible). */
    private fun <T> callWithTimeout(timeoutMs: Long, block: () -> T): T {
        val future = io.submit(Callable(block))
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("No response from the watch after ${timeoutMs / 1000}s.")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "connect_port"
        private const val PAIR_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        fun friendlyError(e: Throwable, pairing: Boolean): String {
            val name = e.javaClass.simpleName
            return when {
                e is IllegalArgumentException -> e.message ?: "Invalid input."
                name == "AdbPairingRequiredException" ->
                    "This phone isn't paired with the watch yet (or the watch forgot it). Tap Pair first."
                e is SocketTimeoutException ->
                    "Timed out. Make sure the phone and watch are on the SAME Wi-Fi network and the watch screen is on."
                e is ConnectException || e is NoRouteToHostException ->
                    if (pairing) "Couldn't reach the pairing port. It closes when you leave the 'Pair new device' screen on the watch — reopen it and use the new port."
                    else "The watch refused the connection. Wireless debugging may be off, or the port changed (it changes every time Wireless debugging is toggled)."
                e is UnknownHostException -> "That IP address isn't valid."
                e is SSLException ->
                    if (pairing) "Pairing failed — the code was wrong or expired. Get a fresh code on the watch and try again."
                    else "Secure handshake failed. The watch may have forgotten this phone — pair again."
                else -> e.message ?: name
            }
        }
    }
}
