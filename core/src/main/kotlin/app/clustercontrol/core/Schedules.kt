package app.clustercontrol.core

/** [ADVANCE] checks the cluster and ticks only when it is idle with jobs waiting; [TICK] always ticks (the manual fallback). */
enum class ScheduleAction { TICK, SYNC, STATUS, ADVANCE }

/** A user-defined "cron job". WorkManager periodic work cannot run more often than every 15 minutes. */
data class Schedule(
    val id: String,
    val name: String,
    val action: ScheduleAction,
    val intervalMinutes: Int,
    val enabled: Boolean = true,
    val requireCharging: Boolean = false,
) {
    val effectiveIntervalMinutes: Int get() = intervalMinutes.coerceIn(MIN_INTERVAL, MAX_INTERVAL)

    companion object {
        const val MIN_INTERVAL = 15
        const val MAX_INTERVAL = 1440
    }
}

enum class NoticeKind { JOB_FINISHED, JOB_FAILED, ALL_DONE }

/** [key] is the SLURM job id (one notification per job); empty for [NoticeKind.ALL_DONE]. */
data class Notice(val kind: NoticeKind, val title: String, val message: String, val key: String = "")

/** What the owner wants to hear between two snapshots: a job that ended (done or failed) and the end of everything. */
fun diffSnapshots(prev: Snapshot?, now: Snapshot): List<Notice> {
    if (prev == null) return emptyList()
    val ended = prev.active?.takeIf { now.active == null || now.active.id != it.id }
    val allDone = now.active == null && now.queue.isEmpty() && (prev.active != null || prev.queue.isNotEmpty())
    if (ended == null && !allDone) return emptyList()
    val failed = now.errorsCount > prev.errorsCount
    val result = if (failed) "non riuscito" else "terminato"
    val label = ended?.let { JobNames.parse(it.name) }?.let { "${it.title} · ${it.subtitle}".trimEnd(' ', '·') }
    return if (allDone) {
        listOf(Notice(NoticeKind.ALL_DONE, "Tutti i job sono finiti", label?.let { "Ultimo: $it ($result)" } ?: "Coda vuota, nessun job in esecuzione"))
    } else {
        listOf(Notice(if (failed) NoticeKind.JOB_FAILED else NoticeKind.JOB_FINISHED, "Job $result", label.orEmpty(), ended!!.id))
    }
}
