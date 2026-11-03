package app.clustercontrol.core

/** When the next scheduled tick is expected; null when no tick ever ran (unknown). May be in the past (overdue). */
fun nextTickAt(lastRunAt: Long?, intervalMinutes: Int, now: Long): Long? =
    lastRunAt?.let { it + intervalMinutes.coerceIn(Schedule.MIN_INTERVAL, Schedule.MAX_INTERVAL) * 60_000L }

/** `23:41`, `1:02:03`; "ora" once the time has come. */
fun formatCountdown(ms: Long): String {
    if (ms <= 0) return "ora"
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** `adesso`, `12 min fa`, `3 h fa`, `2 g fa`. */
fun formatAgo(ms: Long): String {
    val min = ms / 60_000
    return when {
        ms < 45_000 -> "adesso"
        min < 60 -> "${maxOf(min, 1)} min fa"
        min < 60 * 24 -> "${min / 60} h fa"
        else -> "${min / (60 * 24)} g fa"
    }
}

fun percentOf(step: Int?, total: Int?): Int? =
    if (step == null || total == null || total <= 0) null else (step * 100L / total).toInt().coerceIn(0, 100)

/** Moves one element (drag and drop); the other elements keep their order. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    require(from in indices && to in indices) { "move $from -> $to out of ${size}" }
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}
