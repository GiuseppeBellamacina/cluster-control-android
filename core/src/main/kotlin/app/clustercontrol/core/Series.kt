package app.clustercontrol.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Point(val step: Int, val value: Double)

/**
 * The series sidecar written by the runstatus library: `{"total_steps": N, "series": {"loss": [[step, value], ...]}}`.
 * Anything malformed yields nothing rather than failing the poll.
 */
fun parseSeriesJson(text: String): Pair<Int?, Map<String, List<Point>>> {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null to emptyMap()
    val total = root["total_steps"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    val series = (root["series"] as? JsonObject).orEmpty().mapValues { (_, v) ->
        (v as? JsonArray).orEmpty().mapNotNull { p ->
            runCatching {
                val a = p.jsonArray
                Point(a[0].jsonPrimitive.intOrNull ?: return@runCatching null, a[1].jsonPrimitive.doubleOrNull ?: return@runCatching null)
            }.getOrNull()
        }
    }.filterValues { it.isNotEmpty() }
    return total to series
}

/** Uniform subsampling keeping both ends (the latest point is always included). */
fun downsample(points: List<Point>, limit: Int): List<Point> {
    if (limit < 2 || points.size <= limit) return points
    return List(limit) { i -> points[(i.toLong() * (points.size - 1) / (limit - 1)).toInt()] }
}
