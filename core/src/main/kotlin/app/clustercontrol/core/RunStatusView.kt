package app.clustercontrol.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One completion shown from a training run: prompt, output, gold and the reward of every component. */
data class RunSample(
    val prompt: String?,
    val output: String?,
    val gold: String?,
    val match: Boolean?,
    val rewards: Map<String, Double>,
    val total: Double?,
)

/** One planned stage of the run (sft, grpo, eval, ...): [state] is pending, running, done or failed. */
data class StageView(val id: String, val label: String, val icon: String?, val state: String, val step: Int?, val total: Int?)

/** A free chip the training asks to show (e.g. "LoRA", "4bit"). */
data class BadgeView(val label: String, val icon: String?)

/**
 * What the training reports through the runstatus library (the `RUN_STATUS` of the helper): everything the app
 * shows comes from here. Every field may be missing; a file that is not an object yields null.
 */
data class RunStatusView(
    val kind: String?,
    val phase: String?,
    val state: String?,
    val step: Int?,
    val total: Int?,
    val etaSeconds: Long?,
    val epoch: Double?,
    val latest: Map<String, Double>,
    val best: Map<String, Double>,
    val mean: Map<String, Double>,
    val evalActive: Boolean,
    val evalProgress: String?,
    val samples: List<RunSample>,
    val note: String?,
    val gpuMemGb: Double?,
    val method: String? = null,
    val modelFamily: String? = null,
    val modelName: String? = null,
    val modelParams: String? = null,
    val stage: String? = null,
    val stages: List<StageView> = emptyList(),
    val badges: List<BadgeView> = emptyList(),
    val stepsPerSecond: Double? = null,
) {
    /** Reward of each component (`rewards/<name>`), by name. */
    val rewardComponents: Map<String, Double>
        get() = latest.filterKeys { it.startsWith("rewards/") }.mapKeys { it.key.removePrefix("rewards/") }

    /** Final metrics of an evaluation (`eval/...`). */
    val evalResults: Map<String, Double>
        get() = latest.filterKeys { it.startsWith("eval/") }.mapKeys { it.key.removePrefix("eval/") }

    /** Everything else worth listing: not the headline loss/reward, the components, the grammar, nor the eval results. */
    val otherMetrics: Map<String, Double>
        get() = latest.filterKeys {
            it != "loss" && it != "reward" && !it.startsWith("rewards/") && !it.startsWith("eval/")
        }
}

private fun JsonObject?.obj(k: String) = this?.get(k) as? JsonObject
private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject?.num(k: String) = (this?.get(k) as? JsonPrimitive)?.doubleOrNull
private fun JsonObject?.numbers(k: String): Map<String, Double> =
    obj(k)?.mapNotNull { (key, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { key to it } }?.toMap().orEmpty()

private fun parseSample(e: kotlinx.serialization.json.JsonElement): RunSample? = when (e) {
    is JsonPrimitive -> e.takeIf { it.isString }?.contentOrNull?.let { RunSample(it, null, null, null, emptyMap(), null) }
    is JsonObject -> RunSample(
        prompt = e.str("prompt"),
        output = e.str("output"),
        gold = e.str("gold"),
        match = (e["match"] as? JsonPrimitive)?.booleanOrNull,
        rewards = e.numbers("rewards"),
        total = e.num("total"),
    )
    else -> null
}

private fun parseStage(e: kotlinx.serialization.json.JsonElement): StageView? {
    val o = e as? JsonObject ?: return null
    val id = o.str("id") ?: return null
    return StageView(id, o.str("label") ?: id, o.str("icon") ?: id, o.str("state") ?: "pending", o.num("step")?.toInt(), o.num("total")?.toInt())
}

private fun parseBadge(e: kotlinx.serialization.json.JsonElement): BadgeView? = when (e) {
    is JsonPrimitive -> e.takeIf { it.isString }?.contentOrNull?.let { BadgeView(it, null) }
    is JsonObject -> e.str("label")?.let { BadgeView(it, e.str("icon")) }
    else -> null
}

fun parseRunStatus(o: JsonObject?): RunStatusView? {
    if (o == null) return null
    val run = o.obj("run")
    val progress = o.obj("progress")
    val metrics = o.obj("metrics")
    val step = progress.num("step")?.toInt()
    // the app never shows more than 100%: a step past the declared total means the total was wrong
    val total = progress.num("total_steps")?.toInt()?.let { t -> if (step != null) maxOf(t, step) else t }
    return RunStatusView(
        kind = run.str("kind"),
        phase = run.str("phase"),
        state = run.str("state"),
        step = step,
        total = total,
        etaSeconds = progress.num("eta_s")?.toLong(),
        epoch = progress.num("epoch"),
        latest = metrics.numbers("latest"),
        best = metrics.numbers("best"),
        mean = metrics.numbers("mean"),
        evalActive = (o.obj("eval")?.get("active") as? JsonPrimitive)?.booleanOrNull == true,
        evalProgress = o.obj("eval").str("progress"),
        samples = (o["samples"] as? JsonArray).orEmpty().mapNotNull(::parseSample),
        note = o.str("note"),
        gpuMemGb = o.obj("gpu").num("mem_gb"),
        method = run.str("method"),
        modelFamily = run.obj("model").str("family"),
        modelName = run.obj("model").str("name"),
        modelParams = run.obj("model").str("params"),
        stage = run.str("stage"),
        stages = (run?.get("stages") as? JsonArray).orEmpty().mapNotNull(::parseStage),
        badges = (run?.get("badges") as? JsonArray).orEmpty().mapNotNull(::parseBadge),
        stepsPerSecond = progress.num("steps_per_s"),
    )
}
