package app.clustercontrol

import app.clustercontrol.core.Schedule
import app.clustercontrol.core.ScheduleAction
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Schedules, history and state as small JSON files (nothing secret in here). One instance per process. */
class Stores(private val dir: File) {
    private fun read(name: String) = runCatching { Json.parseToJsonElement(File(dir, name).readText()) }.getOrNull()

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }

    @Synchronized
    fun schedules(): List<Schedule> =
        read("schedules.json")?.let { e -> runCatching { e.jsonArray.map { scheduleFromJson(it.jsonObject) } }.getOrNull() }
            ?: SEED.also { saveSchedules(it) }

    @Synchronized
    fun saveSchedules(list: List<Schedule>) = write("schedules.json", JsonArray(list.map { it.toJson() }).toString())

    @Synchronized
    fun history(): List<HistoryEntry> =
        read("history.json")?.let { e -> runCatching { e.jsonArray.map { historyFromJson(it.jsonObject) } }.getOrNull() }.orEmpty()

    /** Newest first, capped at [HISTORY_MAX]. */
    @Synchronized
    fun addHistory(entry: HistoryEntry) =
        write("history.json", JsonArray((listOf(entry) + history()).take(HISTORY_MAX).map { it.toJson() }).toString())

    @Synchronized
    fun state(): AppState =
        read("state.json")?.let { e -> runCatching { stateFromJson(e.jsonObject) }.getOrNull() } ?: AppState()

    @Synchronized
    fun updateState(f: (AppState) -> AppState): AppState = f(state()).also { write("state.json", it.toJson().toString()) }

    companion object {
        const val HISTORY_MAX = 100
        val SEED = listOf(
            Schedule("tick-60", "Tick ogni 60 min", ScheduleAction.TICK, 60, enabled = false),
            Schedule("sync-24h", "Sync script ogni 24 h", ScheduleAction.SYNC, 1440, enabled = false),
        )
    }
}
