package app.clustercontrol

import app.clustercontrol.core.ActiveJob
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.LastJob
import app.clustercontrol.core.Schedule
import app.clustercontrol.core.ScheduleAction
import app.clustercontrol.core.Snapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

fun Snapshot.toJson(): JsonObject = buildJsonObject {
    active?.let { put("active", buildJsonObject { put("id", it.id); put("name", it.name); put("state", it.state) }) }
    put("queue", JsonArray(queue.map { JsonPrimitive(it.toLine()) }))
    lastJob?.let {
        put("last", buildJsonObject {
            put("id", it.id); put("type", it.type); put("config", it.config); put("tag", it.tag); put("retries", it.retries)
        })
    }
    put("stopped", stopped)
    put("errorsCount", errorsCount)
    put("errorsTail", JsonArray(errorsTail.map { JsonPrimitive(it) }))
    put("logPath", logPath)
    put("logTail", logTail)
    put("live", liveStatus ?: JsonNull)
}

fun snapshotFromJson(o: JsonObject): Snapshot = Snapshot(
    active = (o["active"] as? JsonObject)?.let { ActiveJob(it.str("id")!!, it.str("name")!!, it.str("state")!!) },
    queue = o["queue"]!!.jsonArray.mapNotNull { JobEntry.parse(it.jsonPrimitive.content) },
    lastJob = (o["last"] as? JsonObject)?.let {
        LastJob(it.str("id")!!, it.str("type")!!, it.str("config")!!, it.str("tag")!!, (it["retries"] as? JsonPrimitive)?.intOrNull)
    },
    stopped = o["stopped"]!!.jsonPrimitive.boolean,
    errorsCount = o["errorsCount"]!!.jsonPrimitive.int,
    errorsTail = o["errorsTail"]!!.jsonArray.map { it.jsonPrimitive.content },
    logPath = o.str("logPath"),
    logTail = o.str("logTail"),
    liveStatus = o["live"] as? JsonObject,
)

fun Schedule.toJson(): JsonObject = buildJsonObject {
    put("id", id); put("name", name); put("action", action.name)
    put("interval", intervalMinutes); put("enabled", enabled); put("charging", requireCharging)
}

fun scheduleFromJson(o: JsonObject) = Schedule(
    id = o.str("id")!!,
    name = o.str("name")!!,
    action = ScheduleAction.valueOf(o.str("action")!!),
    intervalMinutes = o["interval"]!!.jsonPrimitive.int,
    enabled = o["enabled"]!!.jsonPrimitive.boolean,
    requireCharging = o["charging"]!!.jsonPrimitive.boolean,
)

fun HistoryEntry.toJson(): JsonObject = buildJsonObject {
    put("at", at); put("source", source); put("action", action); put("ok", ok); put("message", message); put("ms", durationMs)
}

fun historyFromJson(o: JsonObject) = HistoryEntry(
    at = o["at"]!!.jsonPrimitive.long,
    source = o.str("source")!!,
    action = o.str("action")!!,
    ok = o["ok"]!!.jsonPrimitive.boolean,
    message = o.str("message")!!,
    durationMs = o["ms"]!!.jsonPrimitive.long,
)

fun AppState.toJson(): JsonObject = buildJsonObject {
    put("autoSuspended", autoSuspended); put("suspendReason", suspendReason)
    put("snapshot", snapshot?.toJson() ?: JsonNull)
    put("snapshotAt", snapshotAt)
    put("schedulesVersion", schedulesVersion)
}

fun stateFromJson(o: JsonObject) = AppState(
    autoSuspended = o["autoSuspended"]!!.jsonPrimitive.boolean,
    suspendReason = o.str("suspendReason"),
    snapshot = (o["snapshot"] as? JsonObject)?.let(::snapshotFromJson),
    snapshotAt = (o["snapshotAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
    schedulesVersion = (o["schedulesVersion"] as? JsonPrimitive)?.intOrNull ?: 0,
)

