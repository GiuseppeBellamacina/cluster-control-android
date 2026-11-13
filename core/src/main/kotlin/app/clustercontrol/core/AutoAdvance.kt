package app.clustercontrol.core

/** What the app should do about an idle cluster with jobs waiting. */
sealed interface Advance {
    /** Nothing to do now (a job runs, the queue is empty, the chain is paused, or the next attempt is not due). */
    data object Wait : Advance

    /** Ask the cluster to start the next job (the helper's tick: it also classifies the finished job and resumes it). */
    data object Tick : Advance

    /** The next job still does not start after every attempt; tell the owner once. */
    data class GiveUp(val message: String) : Advance
}

/**
 * Starts the next job when the previous one is over, instead of waiting for a periodic tick. The cluster scripts keep
 * the real chain logic (retry of a job that hit the time limit, out of memory or a CUDA error, dropping an eval whose
 * train failed): this only decides WHEN to ask, with backoff, and gives up loudly when a job refuses to start.
 */
class AutoAdvance(private val backoffMs: List<Long> = listOf(30_000, 60_000, 120_000, 300_000, 600_000)) {
    private var attempts = 0
    private var nextAt = 0L
    private var gaveUp = false
    private var signature: String? = null

    /** Jobs are waiting and nothing runs: exactly the state in which the chain should move. */
    fun idleWithWork(s: Snapshot) = s.active == null && s.queue.isNotEmpty() && !s.stopped

    fun decide(s: Snapshot?, now: Long): Advance {
        if (s == null || !idleWithWork(s)) {
            reset()
            return Advance.Wait
        }
        // a different head of the queue or a newly finished job is a new situation: start counting again
        val sig = s.queue.first().toLine() + "|" + s.lastJob?.id
        if (sig != signature) {
            reset()
            signature = sig
        }
        return if (gaveUp || now < nextAt) Advance.Wait else Advance.Tick
    }

    /** Report a tick: [after] is the snapshot it returned, null when the tick itself failed. */
    fun ticked(after: Snapshot?, now: Long): Advance {
        if (after != null && !idleWithWork(after)) {
            reset()
            return Advance.Wait
        }
        attempts++
        if (attempts >= backoffMs.size) {
            gaveUp = true
            val next = after?.queue?.firstOrNull()?.let { "${it.tag} " }.orEmpty()
            return Advance.GiveUp("Il job ${next}non parte dopo $attempts tentativi: controlla il cluster o fai un tick a mano.")
        }
        nextAt = now + backoffMs[attempts - 1]
        return Advance.Wait
    }

    /** The owner acted (resumed, ticked by hand, changed the queue): try again from scratch. */
    fun reset() {
        attempts = 0
        nextAt = 0L
        gaveUp = false
        signature = null
    }
}

/** How many times the cluster resumes a train that hit the time limit, ran out of memory or crashed (MAX_RETRIES in cluster/_lib.sh). */
const val MAX_RESUMES = 2

/** One line of the cluster's job error log. [willRetry]: the chain put the job back with `--resume`. */
data class JobError(
    val tag: String,
    val jobType: String,
    val errorType: String,
    val retryNum: Int,
    val willRetry: Boolean,
    val timestamp: String,
    val snippet: String,
)

fun parseJobError(line: String): JobError? = runCatching {
    val o = kotlinx.serialization.json.Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
    fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    JobError(
        tag = s("tag"), jobType = s("job_type"), errorType = s("error_type").ifEmpty { "UNKNOWN" },
        retryNum = s("retry_num").toIntOrNull() ?: 0, willRetry = s("resolved") == "true",
        timestamp = s("timestamp"), snippet = s("error_snippet"),
    )
}.getOrNull()

/** The cluster's wording for an error type, in plain Italian. */
fun describeError(type: String) = when (type) {
    "TIMEOUT" -> "Tempo limite (12 h) raggiunto"
    "OOM" -> "Memoria esaurita"
    "CUDA_ERROR" -> "Errore CUDA"
    "CANCELLED" -> "Annullato"
    else -> "Fallito"
}
