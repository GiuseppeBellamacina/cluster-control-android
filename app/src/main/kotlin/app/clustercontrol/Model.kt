package app.clustercontrol

import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.parseProgress
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Everything the app records in the history; [name] is what is stored. */
enum class Op(val label: String) {
    TICK("Tick"),
    SYNC("Sync script"),
    QUEUE("Coda"),
    KILL("Kill job"),
    PAUSE("Pausa"),
    RESUME("Riprendi"),
    TEST("Prova connessione"),
}

data class HistoryEntry(
    val at: Long,
    val source: String,
    val action: String,
    val ok: Boolean,
    val message: String,
    val durationMs: Long,
)

/** What survives between runs: the circuit breaker, the last snapshot (offline display) and the schedule seed version. */
data class AppState(
    val autoSuspended: Boolean = false,
    val suspendReason: String? = null,
    val snapshot: Snapshot? = null,
    val snapshotAt: Long? = null,
    val schedulesVersion: Int = 0,
)

data class Progress(
    val step: Int?,
    val total: Int?,
    val loss: Double?,
    val reward: Double?,
    val eta: String?,
    val phase: String?,
)

/** live_status.json is the primary source (like the Render driver); the log tail is the fallback. */
fun progressOf(s: Snapshot): Progress {
    val log = parseProgress(s.logTail.orEmpty())
    val live = s.liveStatus
    fun num(k: String) = (live?.get(k) as? JsonPrimitive)?.doubleOrNull
    return Progress(
        step = num("step")?.toInt() ?: log.step,
        total = num("total_steps")?.toInt() ?: log.total,
        loss = num("loss") ?: log.loss,
        reward = num("reward") ?: log.reward,
        eta = log.eta,
        phase = (live?.get("phase") as? JsonPrimitive)?.contentOrNull,
    )
}

/** Series tag of a SLURM job name (`train-<cell>` / `eval-<cell>` -> `<cell>`). */
fun seriesTag(jobName: String) = jobName.removePrefix("train-").removePrefix("eval-")
