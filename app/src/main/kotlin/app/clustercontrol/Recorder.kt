package app.clustercontrol

import app.clustercontrol.core.Schedule
import kotlinx.coroutines.CancellationException

fun interface Notifier {
    fun notify(id: Int, title: String, text: String)
}

object NoticeIds {
    const val FAILURE = 1
    const val SUSPENDED = 2
    const val ALL_DONE = 3
    const val STUCK = 4 // the next job does not start
    const val JOB_BASE = 1000 // + the SLURM job id: one notification per job
}

/** Which schedules WorkManager should be running: one slot per enabled schedule, none while suspended. */
fun desiredWork(schedules: List<Schedule>, state: AppState): List<Schedule> =
    if (state.autoSuspended) emptyList() else schedules.filter { it.enabled }

/**
 * The one place every operation is recorded in the history. One attempt only: failures never throw out of here
 * (so a worker returns success and there is no retry loop) and an authentication failure trips the circuit breaker.
 */
class Recorder(
    private val stores: Stores,
    private val notifier: Notifier,
    private val onSuspended: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Classifies [e]; an auth failure suspends the automatic runs until the user resumes them. */
    fun handleFailure(e: Throwable): ClusterError {
        val err = classify(e)
        if (err.kind == ErrorKind.AUTH) {
            val first = !stores.state.value.autoSuspended
            stores.updateState { it.copy(autoSuspended = true, suspendReason = err.message) }
            if (first) {
                notifier.notify(NoticeIds.SUSPENDED, "Automatismi sospesi", err.message)
                onSuspended()
            }
        }
        return err
    }

    suspend fun record(op: Op, source: String, auto: Boolean, body: suspend () -> String): HistoryEntry {
        val t0 = clock()
        val (ok, message) = try {
            true to body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false to handleFailure(e).message
        }
        val entry = HistoryEntry(t0, source, op.name, ok, message.take(300), clock() - t0)
        stores.addHistory(entry)
        if (!ok && auto) notifier.notify(NoticeIds.FAILURE, "${op.label} non riuscito", message)
        return entry
    }
}
