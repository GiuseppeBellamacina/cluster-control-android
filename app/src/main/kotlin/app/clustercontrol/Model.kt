package app.clustercontrol

import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.parseProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

enum class TransportKind { DIRECT, SSTP }

data class AppSettings(
    val host: String = "gcluster.dmi.unict.it",
    val user: String = "",
    val port: Int = 22,
    val privateKey: String = "",
    val publicKey: String = "",
    val passphrase: String = "",
    val repo: String = "GiuseppeBellamacina/neuro_symbolic_t2g",
    val branch: String = "main",
    val githubToken: String = "",
    val transport: TransportKind = TransportKind.SSTP,
    val vpnHost: String = "vpnstudenti.unict.it",
    val vpnUser: String = "",
    val vpnPassword: String = "",
    val includeSrc: Boolean = false,
) {
    val configured get() = host.isNotBlank() && user.isNotBlank() && privateKey.isNotBlank() &&
        (transport == TransportKind.DIRECT || (vpnHost.isNotBlank() && vpnUser.isNotBlank() && vpnPassword.isNotBlank()))
}

data class HistoryEntry(
    val at: Long,
    val source: String,
    val action: String,
    val ok: Boolean,
    val message: String,
    val durationMs: Long,
)

/** What survives between runs: the automation switches and the last snapshot (for diffs and offline display). */
data class AppState(
    val master: Boolean = true,
    val autoSuspended: Boolean = false,
    val suspendReason: String? = null,
    val snapshot: Snapshot? = null,
    val snapshotAt: Long? = null,
)

data class Progress(
    val step: Int?,
    val total: Int?,
    val loss: Double?,
    val reward: Double?,
    val eta: String?,
    val phase: String?,
) {
    val fraction: Float? get() = if (step != null && total != null && total > 0) (step.toFloat() / total).coerceIn(0f, 1f) else null
}

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

private val RESULT_KEYS = listOf("rouge_l_mean", "exact_match", "validity_rate", "pass_at_1", "bleu_corpus", "chrf_corpus")

/** The key metrics found in an eval json (searched at any depth, first occurrence wins). */
fun resultMetrics(json: String): List<Pair<String, String>> {
    val found = LinkedHashMap<String, Double>()
    fun walk(e: Any?) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                if (k in RESULT_KEYS && k !in found) (v as? JsonPrimitive)?.doubleOrNull?.let { found[k] = it }
                walk(v)
            }
            is Iterable<*> -> e.forEach { walk(it) }
        }
    }
    walk(runCatching { Json.parseToJsonElement(json) }.getOrNull())
    return RESULT_KEYS.mapNotNull { k -> found[k]?.let { k to "%.4f".format(java.util.Locale.ROOT, it) } }
}

fun ageText(now: Long, at: Long?): String {
    if (at == null) return "mai"
    val m = (now - at) / 60_000
    return when {
        m < 1 -> "adesso"
        m < 60 -> "$m min fa"
        m < 24 * 60 -> "${m / 60} h fa"
        else -> "${m / (24 * 60)} g fa"
    }
}
