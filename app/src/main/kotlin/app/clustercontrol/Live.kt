package app.clustercontrol

import app.clustercontrol.core.ClusterConfig
import app.clustercontrol.core.DirectTransport
import app.clustercontrol.core.ClusterSession
import app.clustercontrol.core.HelperApi
import app.clustercontrol.core.HostKeys
import app.clustercontrol.core.Point
import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.Transport
import app.clustercontrol.sstp.SstpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Opens sessions to the cluster with the configuration the owner stored. Blocking. */
class Connector(private val hostKeys: HostKeys) {
    fun config(): ClusterConfig {
        if (!Creds.complete) throw ConfigMissingException(Creds.connectionMissing)
        return ClusterConfig(Creds.host, Creds.sshUser, Creds.sshKey, null, Creds.sshPort)
    }

    fun transport(): Transport {
        if (!Creds.useVpn) return DirectTransport
        val sstp = SstpTransport(Creds.vpnHost, Creds.vpnUser, Creds.vpnPassword)
        return object : Transport {
            override fun open(host: String, port: Int, timeoutMs: Int): java.net.Socket = sstp.openSocket(host, port)
        }
    }

    fun open(transport: Transport = transport(), keepAlive: Boolean = false): ClusterSession =
        ClusterSession.open(config(), transport, hostKeys, 30_000, if (keepAlive) ALIVE_MS else 0)

    companion object {
        const val ALIVE_MS = 15_000
    }
}

enum class Link { OFFLINE, CONNECTING, ONLINE }

data class LiveState(
    val link: Link = Link.OFFLINE,
    val snapshot: Snapshot? = null,
    val snapshotAt: Long? = null,
    val series: Map<String, List<Point>> = emptyMap(),
    val error: ClusterError? = null,
)

/** Wait before reconnect attempt [failures] (1-based); null = give up and go offline until the user refreshes. */
fun backoffMs(failures: Int): Long? = listOf(2_000L, 5_000L, 10_000L).getOrNull(failures - 1)

/**
 * While the app is in the foreground: one tunnel + one ssh session, commands serialised by a mutex, the monitor
 * polled every few seconds. Closed shortly after the app goes to the background.
 */
class LiveSession(
    private val connector: Connector,
    private val stores: Stores,
    private val onFailure: (Throwable) -> ClusterError,
    private val scope: CoroutineScope,
    /** Called after every published snapshot with the previous one and whether the app is in the foreground. */
    private val onChange: (Snapshot?, Snapshot, Boolean) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(
        LiveState(snapshot = stores.state.value.snapshot, snapshotAt = stores.state.value.snapshotAt),
    )
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var session: ClusterSession? = null // guarded by mutex
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var closer: Job? = null
    private var foreground = false

    @Synchronized
    fun start() {
        foreground = true
        closer?.cancel()
        if (loop == null) loop = scope.launch(Dispatchers.IO) { pollLoop() }
    }

    @Synchronized
    fun stop() {
        foreground = false
        closer = scope.launch {
            delay(CLOSE_AFTER_MS)
            val stopped = synchronized(this@LiveSession) {
                (!foreground).also {
                    if (it) {
                        loop?.cancel()
                        loop = null
                    }
                }
            }
            if (stopped) {
                mutex.withLock { dropSession() }
                _state.update { it.copy(link = Link.OFFLINE) }
            }
        }
    }

    /** Poll now (also wakes a loop that gave up). */
    fun refresh() {
        kick.trySend(Unit)
    }

    private fun dropSession() {
        session?.let { runCatching { it.close() } }
        session = null
    }

    private fun connect(): ClusterSession {
        _state.update { it.copy(link = Link.CONNECTING) }
        return connector.open(keepAlive = true).also {
            session = it
            _state.update { s -> s.copy(link = Link.ONLINE) }
        }
    }

    /**
     * Runs [block] on the live session. With [keep] false and no live session it uses a short-lived one instead
     * (scheduled workers: the VPN is only open during the action).
     */
    suspend fun <T> exec(keep: Boolean = true, block: (ClusterSession, HelperApi) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val live = session
            if (live == null && !keep) {
                connector.open().use { block(it, HelperApi(it, Creds.remoteRoot)) }
            } else {
                try {
                    val s = live ?: connect()
                    block(s, HelperApi(s, Creds.remoteRoot))
                } catch (e: Exception) {
                    if (classify(e).kind == ErrorKind.UNREACHABLE) dropSession()
                    throw e
                }
            }
        }
    }

    /** Runs [block] with no live session open (the connection test: one VPN login at a time); the loop reconnects after. */
    suspend fun <T> exclusive(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            dropSession()
            _state.update { it.copy(link = Link.CONNECTING) }
            block()
        }
    }

    fun publish(snap: Snapshot) {
        val at = clock()
        val prev = stores.state.value.snapshot
        stores.updateState { it.copy(snapshot = snap, snapshotAt = at) }
        _state.update { it.copy(snapshot = snap, snapshotAt = at, error = null, link = Link.ONLINE) }
        onChange(prev, snap, foreground)
    }

    private suspend fun poll(withSeries: Boolean) {
        val (snap, series) = exec { _, h ->
            val snap = h.monitor(MONITOR_LINES)
            val changed = snap.active?.id != _state.value.snapshot?.active?.id
            val tag = snap.active?.name?.let(::seriesTag)
            // a failing series fetch must not fail the poll
            val series = if (tag != null && (withSeries || changed)) {
                runCatching { h.timeseries(tag) }.getOrNull()?.let { if (it.match) it.series else emptyMap() }
            } else {
                null
            }
            snap to series
        }
        publish(snap)
        _state.update { it.copy(series = if (snap.active == null) emptyMap() else series ?: it.series) }
    }

    private suspend fun pollLoop() {
        var failures = 0
        var seriesAt = 0L
        while (currentCoroutineContext().isActive) {
            try {
                val due = clock() - seriesAt >= SERIES_EVERY_MS
                poll(due)
                if (due) seriesAt = clock()
                failures = 0
                withTimeoutOrNull(POLL_EVERY_MS) { kick.receive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = onFailure(e)
                failures++
                val retry = if (err.kind == ErrorKind.UNREACHABLE) backoffMs(failures) else null
                val giveUp = err.kind == ErrorKind.AUTH || err.kind == ErrorKind.CONFIG ||
                    (err.kind == ErrorKind.UNREACHABLE && retry == null)
                if (giveUp) {
                    _state.update { it.copy(link = Link.OFFLINE, error = err) }
                    kick.receive()
                    failures = 0
                } else {
                    _state.update { it.copy(link = if (retry != null) Link.CONNECTING else it.link, error = err) }
                    withTimeoutOrNull(retry ?: POLL_EVERY_MS) { kick.receive() }
                }
            }
        }
    }

    companion object {
        const val POLL_EVERY_MS = 8_000L
        const val SERIES_EVERY_MS = 30_000L
        const val CLOSE_AFTER_MS = 20_000L
        const val MONITOR_LINES = 300
    }
}
