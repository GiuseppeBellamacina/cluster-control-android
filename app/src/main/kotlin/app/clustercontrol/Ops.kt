package app.clustercontrol

import app.clustercontrol.core.ClusterConfig
import app.clustercontrol.core.ClusterSession
import app.clustercontrol.core.DirectTransport
import app.clustercontrol.core.GithubFetcher
import app.clustercontrol.core.HelperApi
import app.clustercontrol.core.HelperMissingException
import app.clustercontrol.core.HostKeys
import app.clustercontrol.core.ScheduleAction
import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.SshRemoteStore
import app.clustercontrol.core.SyncEngine
import app.clustercontrol.core.SyncItem
import app.clustercontrol.core.SyncOptions
import app.clustercontrol.core.Transport
import app.clustercontrol.core.diffSnapshots
import app.clustercontrol.sstp.SstpTransport
import java.net.InetAddress

/** Seam for the transport: DIRECT is the system network; SSTP is wired in later. */
fun interface TransportProvider {
    /** [onStage] receives the SSTP handshake stages (never secrets). */
    fun get(settings: AppSettings, onStage: (String, String) -> Unit): Transport
}

object DefaultTransportProvider : TransportProvider {
    override fun get(settings: AppSettings, onStage: (String, String) -> Unit): Transport = when (settings.transport) {
        TransportKind.DIRECT -> DirectTransport
        TransportKind.SSTP -> {
            val sstp = SstpTransport(settings.vpnHost.trim(), settings.vpnUser.trim(), settings.vpnPassword, onStage = onStage)
            object : Transport {
                override fun open(host: String, port: Int, timeoutMs: Int): java.net.Socket = sstp.openSocket(host, port)
            }
        }
    }
}

/** Every blocking cluster interaction of the app; call from a background thread only. */
class ClusterOps(
    private val settings: () -> AppSettings,
    private val stores: Stores,
    private val transports: TransportProvider,
    private val hostKeys: HostKeys,
    private val notifier: Notifier,
    onSuspended: () -> Unit,
) {
    private val recorder = Recorder(stores, notifier, onSuspended)

    fun handleFailure(e: Throwable) = recorder.handleFailure(e)

    fun <T> withCluster(block: (ClusterSession, HelperApi) -> T): T {
        val s = settings()
        if (!s.configured) throw ConfigMissingException()
        val cfg = ClusterConfig(s.host.trim(), s.user.trim(), s.privateKey, s.passphrase.ifBlank { null }, s.port)
        return ClusterSession.open(cfg, transports.get(s) { _, _ -> }, hostKeys).use { block(it, HelperApi(it)) }
    }

    /** Persists [snap] as the latest known state; [notify] raises local notifications for what changed since the previous one. */
    fun storeSnapshot(snap: Snapshot, notify: Boolean) {
        val prev = stores.state().snapshot
        stores.updateState { it.copy(snapshot = snap, snapshotAt = System.currentTimeMillis()) }
        if (notify) diffSnapshots(prev, snap).forEach { notifier.notify(NoticeIds.CHAIN, "ClusterControl", it.message) }
    }

    fun mutate(block: (HelperApi) -> Snapshot) = withCluster { _, h -> storeSnapshot(block(h), false) }

    fun refresh() = mutate { it.monitor(200) }

    private fun engine(s: AppSettings) = SyncEngine(GithubFetcher(s.githubToken.ifBlank { null }), s.repo.trim(), s.branch.trim())

    fun syncPlan(): List<SyncItem> = withCluster { session, _ ->
        val s = settings()
        engine(s).plan(SshRemoteStore(session), SyncOptions(s.includeSrc))
    }

    private fun sync(session: ClusterSession): String {
        val s = settings()
        val r = engine(s).apply(SshRemoteStore(session), SyncOptions(s.includeSrc))
        if (r.errors.isNotEmpty()) throw ActionFailed("Sync: ${r.errors.size} errori (${r.errors.first()})")
        return "Sync ok: ${r.updated.size} aggiornati su ${r.checked}"
    }

    private fun describe(snap: Snapshot) =
        (snap.active?.let { "attivo ${it.name}" } ?: "nessun job attivo") + ", coda ${snap.queue.size}" +
            if (snap.stopped) ", in pausa" else ""

    fun runAction(action: ScheduleAction, source: String, auto: Boolean): HistoryEntry =
        recorder.record(action, source, auto) {
            withCluster { session, helper ->
                when (action) {
                    ScheduleAction.TICK -> {
                        var prefix = ""
                        val r = try {
                            helper.tick()
                        } catch (e: HelperMissingException) {
                            prefix = sync(session) + "; "
                            helper.tick()
                        }
                        storeSnapshot(r.snapshot, auto)
                        if (r.tickFailed) throw ActionFailed("Tick fallito sul cluster (${describe(r.snapshot)})")
                        prefix + "Tick ok: " + describe(r.snapshot)
                    }
                    ScheduleAction.SYNC -> sync(session)
                    ScheduleAction.STATUS -> helper.monitor(200).let { storeSnapshot(it, auto); "Stato: " + describe(it) }
                }
            }
        }

    /** Staged connection test: one line per step with timing; stops at the first failure. Never prints secrets. */
    fun lab(emit: (String) -> Unit) {
        val s = settings()
        fun step(name: String, block: () -> String): Boolean {
            val t0 = System.currentTimeMillis()
            return try {
                val detail = block()
                emit("[OK]  $name  ${System.currentTimeMillis() - t0} ms  $detail".trimEnd())
                true
            } catch (e: Exception) {
                emit("[KO]  $name  ${System.currentTimeMillis() - t0} ms  ${handleFailure(e).message}")
                false
            }
        }
        if (!s.configured) return emit("[KO]  configurazione  ${ConfigMissingException().message}")
        val cfg = ClusterConfig(s.host.trim(), s.user.trim(), s.privateKey, s.passphrase.ifBlank { null }, s.port)
        val transport = transports.get(s) { name, detail -> emit("      sstp $name  $detail".trimEnd()) }
        if (s.transport == TransportKind.DIRECT) {
            if (!step("risoluzione host") { InetAddress.getByName(cfg.host).hostAddress.orEmpty() }) return
            if (!step("apertura trasporto DIRECT") { transport.open(cfg.host, cfg.port, 10_000).use { "connesso" } }) return
        } // SSTP: the tunnel stages print during the ssh step (one VPN login only)
        var session: ClusterSession? = null
        if (!step("autenticazione ssh") { session = ClusterSession.open(cfg, transport, hostKeys, 45_000); "ok" }) return
        session!!.use {
            step("helper status") { describe(HelperApi(it).status()) }
        }
    }
}
