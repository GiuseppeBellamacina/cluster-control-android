package app.clustercontrol

import app.clustercontrol.core.ClusterSession
import app.clustercontrol.core.GithubFetcher
import app.clustercontrol.core.HelperApi
import app.clustercontrol.core.HelperMissingException
import app.clustercontrol.core.ScheduleAction
import app.clustercontrol.core.ScriptStatus
import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.SshRemoteStore
import app.clustercontrol.core.SyncEngine
import app.clustercontrol.core.Transport
import app.clustercontrol.core.diffSnapshots

enum class StepState { IDLE, RUNNING, OK, FAIL }

data class ConnStep(val name: String, val state: StepState = StepState.IDLE, val ms: Long? = null, val error: String? = null)

/** Every cluster interaction of the app, each recorded in the history. Scheduled runs pass `auto = true`. */
class ClusterOps(
    private val live: LiveSession,
    private val connector: Connector,
    private val stores: Stores,
    private val notifier: Notifier,
    private val recorder: Recorder,
) {
    private fun engine() = SyncEngine(GithubFetcher(), Seed.REPO, Seed.BRANCH)

    /** Persists [snap] as the latest known state; [notify] raises local notifications for what changed since the previous one. */
    private fun storeSnapshot(snap: Snapshot, notify: Boolean) {
        val prev = stores.state.value.snapshot
        if (notify) diffSnapshots(prev, snap).forEach { notifier.notify(NoticeIds.CHAIN, "ClusterControl", it.message) }
        live.publish(snap)
    }

    /** [block] followed by a fresh monitor, so the log and progress are current right after the change. */
    private suspend fun mutating(auto: Boolean, block: (ClusterSession, HelperApi) -> Snapshot): Snapshot =
        live.exec(keep = !auto) { s, h ->
            val r = block(s, h)
            runCatching { h.monitor(LiveSession.MONITOR_LINES) }.getOrDefault(r)
        }

    private fun describe(snap: Snapshot) =
        (snap.active?.let { "attivo ${it.name}" } ?: "nessun job attivo") + ", coda ${snap.queue.size}" +
            if (snap.stopped) ", in pausa" else ""

    private fun syncScripts(session: ClusterSession): String {
        val r = engine().apply(SshRemoteStore(session))
        if (r.errors.isNotEmpty()) throw ActionFailed("Sync: ${r.errors.size} errori (${r.errors.first()})")
        return "Sync ok: ${r.updated.size} aggiornati su ${r.checked}"
    }

    suspend fun run(action: ScheduleAction, source: String, auto: Boolean) = when (action) {
        ScheduleAction.TICK -> tick(source, auto)
        ScheduleAction.SYNC -> sync(source, auto)
        ScheduleAction.STATUS -> status(source, auto)
    }

    suspend fun tick(source: String, auto: Boolean) = recorder.record(Op.TICK, source, auto) {
        var prefix = ""
        var failed = false
        val snap = mutating(auto) { s, h ->
            val r = try {
                h.tick()
            } catch (e: HelperMissingException) {
                prefix = syncScripts(s) + "; "
                h.tick()
            }
            failed = r.tickFailed
            r.snapshot
        }
        storeSnapshot(snap, auto)
        if (failed) throw ActionFailed("Tick fallito sul cluster (${describe(snap)})")
        prefix + "Tick ok: " + describe(snap)
    }

    suspend fun status(source: String, auto: Boolean) = recorder.record(Op.STATUS, source, auto) {
        val snap = mutating(auto) { _, h -> h.monitor(LiveSession.MONITOR_LINES) }
        storeSnapshot(snap, auto)
        "Stato: " + describe(snap)
    }

    suspend fun sync(source: String, auto: Boolean) =
        recorder.record(Op.SYNC, source, auto) { live.exec(keep = !auto) { s, _ -> syncScripts(s) } }

    /** A queue / chain change: [block] returns the new snapshot; [ok] is the history text. */
    suspend fun mutate(op: Op, ok: String, block: (HelperApi) -> Snapshot) = recorder.record(op, "manuale", false) {
        storeSnapshot(mutating(false) { _, h -> block(h) }, false)
        ok
    }

    suspend fun scriptStatus(): List<ScriptStatus> = live.exec { s, _ -> engine().status(SshRemoteStore(s)) }

    /** VPN -> SSH -> Cluster, stopping at the first failure; [onSteps] gets every intermediate state. */
    suspend fun testConnection(onSteps: (List<ConnStep>) -> Unit) = recorder.record(Op.TEST, "manuale", false) {
        live.exclusive { runTest(onSteps) }
    }

    private fun runTest(onSteps: (List<ConnStep>) -> Unit): String {
        var steps = listOf(ConnStep("VPN", StepState.RUNNING), ConnStep("SSH"), ConnStep("Cluster"))
        fun show(i: Int, step: ConnStep) {
            steps = steps.toMutableList().also { it[i] = step }
            onSteps(steps)
        }
        onSteps(steps)
        var stage = 0
        var t0 = System.currentTimeMillis()
        try {
            connector.config()
            val base = connector.transport()
            val timed = object : Transport {
                override fun open(host: String, port: Int, timeoutMs: Int) = base.open(host, port, timeoutMs).also {
                    show(0, ConnStep("VPN", StepState.OK, System.currentTimeMillis() - t0))
                    show(1, ConnStep("SSH", StepState.RUNNING))
                    stage = 1
                    t0 = System.currentTimeMillis()
                }
            }
            connector.open(timed).use { session ->
                show(1, ConnStep("SSH", StepState.OK, System.currentTimeMillis() - t0))
                show(2, ConnStep("Cluster", StepState.RUNNING))
                stage = 2
                t0 = System.currentTimeMillis()
                HelperApi(session).status()
                show(2, ConnStep("Cluster", StepState.OK, System.currentTimeMillis() - t0))
            }
        } catch (e: Exception) {
            show(stage, ConnStep(steps[stage].name, StepState.FAIL, System.currentTimeMillis() - t0, classify(e).message))
            throw e
        }
        return steps.joinToString(", ") { "${it.name} ${it.ms} ms" }
    }
}
