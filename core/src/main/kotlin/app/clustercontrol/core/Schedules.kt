package app.clustercontrol.core

enum class ScheduleAction { TICK, SYNC }

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

enum class NoticeKind { JOB_STARTED, JOB_FINISHED, QUEUE_EMPTY, NEW_ERRORS, PAUSED, RESUMED }

data class Notice(val kind: NoticeKind, val message: String)

/** What changed between two snapshots, for local notifications. */
fun diffSnapshots(prev: Snapshot?, now: Snapshot): List<Notice> {
    if (prev == null) return emptyList()
    val out = mutableListOf<Notice>()
    val before = prev.active
    val after = now.active
    if (before != null && (after == null || after.id != before.id)) {
        out += Notice(NoticeKind.JOB_FINISHED, "Job ${before.name} (${before.id}) is no longer running")
    }
    if (after != null && after.id != before?.id) {
        out += Notice(NoticeKind.JOB_STARTED, "Job ${after.name} (${after.id}) started")
    }
    if (prev.queue.isNotEmpty() && now.queue.isEmpty() && after == null) {
        out += Notice(NoticeKind.QUEUE_EMPTY, "Queue is empty and nothing is running")
    }
    if (now.errorsCount > prev.errorsCount) {
        out += Notice(NoticeKind.NEW_ERRORS, "${now.errorsCount - prev.errorsCount} new chain error(s)")
    }
    if (now.stopped && !prev.stopped) out += Notice(NoticeKind.PAUSED, "Chain paused")
    if (!now.stopped && prev.stopped) out += Notice(NoticeKind.RESUMED, "Chain resumed")
    return out
}
