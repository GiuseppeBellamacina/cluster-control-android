package app.clustercontrol

import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.parseRunStatus

/** Everything the app records in the history; [name] is what is stored. */
enum class Op(val label: String) {
    TICK("Tick"),
    STATUS("Stato"),
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
    val etaSeconds: Long?,
    val phase: String?,
)

/** The runstatus file of the active job is the only source: no log parsing. Absent file: all null. */
fun progressOf(s: Snapshot): Progress {
    val v = parseRunStatus(s.runStatus)
    return Progress(
        step = v?.step,
        total = v?.total,
        loss = v?.latest?.get("loss"),
        reward = v?.latest?.get("reward"),
        etaSeconds = v?.etaSeconds,
        phase = v?.phase,
    )
}

/** Series tag of a SLURM job name (`train-<cell>` / `eval-<cell>` -> `<cell>`). */
fun seriesTag(jobName: String) = jobName.removePrefix("train-").removePrefix("eval-")
