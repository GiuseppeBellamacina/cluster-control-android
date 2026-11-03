package app.clustercontrol.core

data class LogProgress(
    val step: Int?,
    val total: Int?,
    val loss: Double?,
    val reward: Double?,
    val eta: String?,
)

private val KV_STEP = Regex("""^\s+step=(\d+)\s+loss=([-+0-9.eE]+).*$""", RegexOption.MULTILINE)
private val KV_REWARD = Regex("""reward=([+-]?\d+\.\d+)""")
private val STAGE_START = Regex("""\[stage (\d+)] steps=(\d+)""")
private val TQDM = Regex("""(\d+)%\|[^|\n]*\|\s*(\d+)/(\d+)\s*\[([\d:]+)<([\d:?]+)""")
private val EVAL_PROGRESS = Regex("""eval progress:\s*(\d+)/(\d+)""")

/** Progress of the active job from its log tail (subset of src/utils/chain_monitor.py). */
fun parseProgress(logTail: String): LogProgress {
    val kv = KV_STEP.findAll(logTail).lastOrNull()
    val total = STAGE_START.findAll(logTail).lastOrNull()?.groupValues?.get(2)?.toInt()
    if (kv != null) {
        return LogProgress(
            step = kv.groupValues[1].toInt(),
            total = total,
            loss = kv.groupValues[2].toDoubleOrNull(),
            reward = KV_REWARD.find(kv.value)?.groupValues?.get(1)?.toDoubleOrNull(),
            eta = TQDM.findAll(logTail).lastOrNull()?.groupValues?.get(5),
        )
    }
    TQDM.findAll(logTail).lastOrNull()?.let {
        return LogProgress(it.groupValues[2].toInt(), it.groupValues[3].toInt(), null, null, it.groupValues[5])
    }
    EVAL_PROGRESS.findAll(logTail).lastOrNull()?.let {
        return LogProgress(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null, null, null)
    }
    return LogProgress(null, total, null, null, null)
}

/** `h:mm:ss` or `mm:ss` (tqdm ETA) to seconds; null for `?` placeholders and anything else. */
fun parseEtaSeconds(eta: String?): Long? {
    val parts = eta?.split(':') ?: return null
    if (parts.size !in 2..3) return null
    val n = parts.map { it.toLongOrNull() ?: return null }
    return n.fold(0L) { acc, v -> acc * 60 + v }
}

data class Point(val step: Int, val value: Double)

private val SERIES_LINE = Regex("""^\s+step=(\d+)\s+(.*)$""")
private val KV_TOKEN = Regex("""([A-Za-z_][A-Za-z0-9_]*)=([-+]?[0-9.]+(?:[eE][-+]?\d+)?)""")

/** `step=N loss=.. reward=.. lr=..` lines -> one series per metric. */
fun parseSeries(text: String): Map<String, List<Point>> {
    val out = LinkedHashMap<String, MutableList<Point>>()
    for (line in text.lineSequence()) {
        val m = SERIES_LINE.matchEntire(line) ?: continue
        val step = m.groupValues[1].toInt()
        for (t in KV_TOKEN.findAll(m.groupValues[2])) {
            val v = t.groupValues[2].toDoubleOrNull() ?: continue
            out.getOrPut(t.groupValues[1]) { mutableListOf() }.add(Point(step, v))
        }
    }
    return out
}

/** Uniform subsampling keeping both ends (the latest point is always included). */
fun downsample(points: List<Point>, limit: Int): List<Point> {
    if (limit < 2 || points.size <= limit) return points
    return List(limit) { i -> points[(i.toLong() * (points.size - 1) / (limit - 1)).toInt()] }
}
