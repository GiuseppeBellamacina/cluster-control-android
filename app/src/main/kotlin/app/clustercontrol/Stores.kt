package app.clustercontrol

import app.clustercontrol.core.Schedule
import app.clustercontrol.core.ScheduleAction
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Schedules, history, state and cached GitHub data as small files (nothing secret in here). One instance per process. */
class Stores(private val dir: File) {
    private fun read(name: String) = runCatching { Json.parseToJsonElement(File(dir, name).readText()) }.getOrNull()

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }

    private val _state = MutableStateFlow(
        read("state.json")?.let { e -> runCatching { stateFromJson(e.jsonObject) }.getOrNull() } ?: AppState(),
    )
    val state: StateFlow<AppState> = _state

    private val _history = MutableStateFlow(
        read("history.json")?.let { e -> runCatching { e.jsonArray.map { historyFromJson(it.jsonObject) } }.getOrNull() }.orEmpty(),
    )
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _schedules = MutableStateFlow(loadSchedules())
    val schedules: StateFlow<List<Schedule>> = _schedules

    /** Old installs keep schedules that were both off: reseed the defaults once per [SCHEDULES_VERSION]. */
    private fun loadSchedules(): List<Schedule> {
        val stored = read("schedules.json")?.let { e -> runCatching { e.jsonArray.map { scheduleFromJson(it.jsonObject) } }.getOrNull() }
        if (stored != null && _state.value.schedulesVersion >= SCHEDULES_VERSION) return stored
        write("schedules.json", JsonArray(SEED.map { it.toJson() }).toString())
        updateState { it.copy(schedulesVersion = SCHEDULES_VERSION) }
        return SEED
    }

    @Synchronized
    fun saveSchedules(list: List<Schedule>) {
        write("schedules.json", JsonArray(list.map { it.toJson() }).toString())
        _schedules.value = list
    }

    /** Newest first, capped at [HISTORY_MAX]. */
    @Synchronized
    fun addHistory(entry: HistoryEntry) = saveHistory((listOf(entry) + _history.value).take(HISTORY_MAX))

    @Synchronized
    fun clearHistory() = saveHistory(emptyList())

    private fun saveHistory(list: List<HistoryEntry>) {
        write("history.json", JsonArray(list.map { it.toJson() }).toString())
        _history.value = list
    }

    @Synchronized
    fun updateState(f: (AppState) -> AppState): AppState = f(_state.value).also {
        write("state.json", it.toJson().toString())
        _state.value = it
    }

    fun cached(name: String): String? = runCatching { File(dir, name).readText() }.getOrNull()

    fun cache(name: String, text: String) = write(name, text)

    companion object {
        const val HISTORY_MAX = 200
        const val SCHEDULES_VERSION = 3
        val SEED = listOf(
            Schedule("advance-15", "Controlla la coda ogni 15 min", ScheduleAction.ADVANCE, 15, enabled = true),
            Schedule("tick-60", "Tick forzato ogni 60 min", ScheduleAction.TICK, 60, enabled = false),
            Schedule("sync-24h", "Sync script ogni 24 h", ScheduleAction.SYNC, 1440, enabled = false),
        )
    }
}
