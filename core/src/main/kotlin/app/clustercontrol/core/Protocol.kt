package app.clustercontrol.core

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Entry separator of the helper protocol (unit separator: never used inside an entry). */
const val ENTRY_SEP = '\u001f'

class ProtocolException(message: String) : Exception(message)

/** `KEY=VALUE` lines of cluster_helper.sh output; anything else (stderr noise, blank lines) is ignored. */
fun parseKv(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lineSequence()) {
        val i = line.indexOf('=')
        if (i <= 0) continue
        val key = line.substring(0, i)
        if (!key.all { it.isUpperCase() || it.isDigit() || it == '_' }) continue
        out[key] = line.substring(i + 1).trimEnd('\r')
    }
    return out
}

/** A queue line `type:cfg:tag[:extra]` (cfg is a path, never contains ':'; extra may). */
data class JobEntry(val type: String, val config: String, val tag: String, val extra: String? = null) {
    fun toLine(): String = listOfNotNull(type, config, tag, extra).joinToString(":")

    companion object {
        fun parse(line: String): JobEntry? {
            val p = line.split(':', limit = 4)
            return if (p.size < 3) null else JobEntry(p[0], p[1], p[2], p.getOrNull(3))
        }
    }
}

data class ActiveJob(val id: String, val name: String, val state: String)

data class LastJob(val id: String, val type: String, val config: String, val tag: String, val retries: Int?)

data class Snapshot(
    val active: ActiveJob?,
    val queue: List<JobEntry>,
    val lastJob: LastJob?,
    val stopped: Boolean,
    val errorsCount: Int,
    val errorsTail: List<String>,
    val logPath: String? = null,
    val logTail: String? = null,
    /** The runstatus file of the active job (`RUN_STATUS`), absent when the job writes none or it is stale. */
    val runStatus: JsonObject? = null,
)

fun parseSnapshot(stdout: String): Snapshot {
    val kv = parseKv(stdout)
    if (kv["STATUS_OK"] != "1") {
        throw ProtocolException("helper output without STATUS_OK=1: ${stdout.take(200)}")
    }
    val active = kv["ACTIVE_JOB"]?.takeIf { it.isNotBlank() }?.split('|', limit = 3)
        ?.let { if (it.size == 3) ActiveJob(it[0], it[1], it[2]) else null }
    val queue = kv["QUEUE"].orEmpty().split(ENTRY_SEP).mapNotNull { JobEntry.parse(it) }
    val last = kv["LAST_JOB"]?.takeIf { it.isNotBlank() }?.split(':', limit = 6)
        ?.let { if (it.size >= 4) LastJob(it[0], it[1], it[2], it[3], it.getOrNull(4)?.toIntOrNull()) else null }
    val errors = runCatching {
        Json.parseToJsonElement(kv["ERRORS_TAIL"].orEmpty().ifBlank { "[]" }).jsonArray.map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())
    val logTail = kv["LOG_TAIL_B64"]?.takeIf { it.isNotBlank() }
        ?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
    val run = kv["RUN_STATUS"]?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    return Snapshot(
        active = active,
        queue = queue,
        lastJob = last,
        stopped = kv["STOPPED"] == "1",
        errorsCount = kv["ERRORS_COUNT"]?.toIntOrNull() ?: 0,
        errorsTail = errors,
        logPath = kv["LOG_PATH"]?.takeIf { it.isNotBlank() },
        logTail = logTail,
        runStatus = run,
    )
}

private val TAG_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val EXTRA_RE = Regex("[-A-Za-z0-9][A-Za-z0-9 ._=/:,-]{0,127}")

/** Same validation as remote/app.py::build_entry; [configPath] must already be resolved. */
fun buildEntry(type: String, configPath: String, tag: String, mode: String? = null): JobEntry {
    require(type == "train" || type == "eval") { "type must be train or eval: $type" }
    require(configPath.isNotBlank() && ':' !in configPath) { "invalid config path: $configPath" }
    require(TAG_RE.matches(tag)) { "invalid tag: $tag" }
    val extra = mode?.takeIf { it.isNotBlank() }
    if (extra != null) require(EXTRA_RE.matches(extra)) { "invalid mode: $extra" }
    return JobEntry(type, configPath, tag, extra)
}
