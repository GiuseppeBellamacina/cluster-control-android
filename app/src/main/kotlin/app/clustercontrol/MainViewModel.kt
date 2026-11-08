package app.clustercontrol

import android.app.Application
import android.provider.Settings
import android.os.PowerManager
import android.net.Uri
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clustercontrol.core.ConfigCatalog
import app.clustercontrol.core.ConfigCell
import app.clustercontrol.core.GithubFetcher
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.Point
import app.clustercontrol.core.Release
import app.clustercontrol.core.Updates
import app.clustercontrol.core.Preset
import app.clustercontrol.core.Schedule
import app.clustercontrol.core.SyncStatus
import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.SyncManifest
import app.clustercontrol.core.buildEntry
import app.clustercontrol.core.moved
import app.clustercontrol.core.parsePresets
import app.clustercontrol.core.resolveJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ScriptsUi(val status: SyncStatus? = null, val busy: Boolean = false, val result: String? = null)

data class UpdateUi(
    val checking: Boolean = false,
    val release: Release? = null,
    val newer: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val checked: Boolean = false,
)

data class ConnUi(val steps: List<ConnStep> = emptyList(), val running: Boolean = false)

data class UiState(
    val snapshot: Snapshot? = null,
    val snapshotAt: Long? = null,
    val link: Link = Link.OFFLINE,
    val error: ClusterError? = null,
    val series: Map<String, List<Point>> = emptyMap(),
    val queue: List<JobEntry> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val suspended: Boolean = false,
    val suspendReason: String? = null,
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val catalog: List<ConfigCell> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val scripts: ScriptsUi = ScriptsUi(),
    val conn: ConnUi = ConnUi(),
    val batteryRestricted: Boolean = false,
    val update: UpdateUi = UpdateUi(),
)

/** What the screens can ask for (the view model implements it; previews use a no-op). */
interface Actions {
    fun refresh()
    fun tick()
    fun pauseResume()
    fun kill()
    fun clearQueue()
    fun queueMove(from: Int, to: Int)
    fun queueRemove(index: Int): JobEntry?
    fun queueUndo(index: Int, entry: JobEntry)
    fun queueFlush()
    fun addJob(cell: ConfigCell, type: String)
    fun addPresets(presets: List<Preset>, replace: Boolean, startNow: Boolean)
    fun loadCatalog()
    fun saveSchedules(list: List<Schedule>)
    fun runSchedule(s: Schedule)
    fun resumeAuto()
    fun checkScripts()
    fun updateScripts()
    fun testConnection()
    fun clearHistory()
    fun requestBatteryExemption()
    fun checkUpdate()
    fun installUpdate()
}

class MainViewModel(app: Application) : AndroidViewModel(app), Actions {
    private val g = ClusterApp.graph
    private val editor = QueueEditor(g.stores.state.value.snapshot?.queue.orEmpty())
    private val local = MutableStateFlow(UiState(queue = editor.items).withCache())
    private var inFlight = 0

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private fun merge(l: LiveState, st: AppState, sch: List<Schedule>, hist: List<HistoryEntry>, loc: UiState) = loc.copy(
        snapshot = l.snapshot, snapshotAt = l.snapshotAt, link = l.link, error = l.error, series = l.series,
        schedules = sch, history = hist, suspended = st.autoSuspended, suspendReason = st.suspendReason,
    )

    val ui: StateFlow<UiState> = combine(g.live.state, g.stores.state, g.stores.schedules, g.stores.history, local) { l, st, sch, hist, loc -> merge(l, st, sch, hist, loc) }
        .stateIn(
            viewModelScope, SharingStarted.Eagerly,
            merge(g.live.state.value, g.stores.state.value, g.stores.schedules.value, g.stores.history.value, local.value),
        )

    init {
        checkUpdate()
        viewModelScope.launch {
            g.live.state.map { it.snapshot?.queue }.distinctUntilChanged().collect { q ->
                if (q != null) {
                    editor.adopt(q)
                    publishQueue()
                }
            }
        }
    }

    private fun UiState.withCache(): UiState {
        val paths = g.stores.cached(CATALOG_CACHE)?.lines()?.filter { it.isNotBlank() }
        val presets = g.stores.cached(PRESETS_CACHE)?.let { runCatching { parsePresets(it) }.getOrNull() }
        return copy(catalog = paths?.let(ConfigCatalog::fromTree).orEmpty(), presets = presets.orEmpty())
    }

    private fun publishQueue() = local.update { it.copy(queue = editor.items) }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            inFlight++
            local.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                if (--inFlight == 0) local.update { it.copy(busy = false) }
            }
        }
    }

    private fun report(e: HistoryEntry, ok: String) {
        _events.tryEmit(if (e.ok) ok else e.message)
    }

    override fun refresh() {
        viewModelScope.launch {
            local.update { it.copy(refreshing = true) }
            val at = g.live.state.value.snapshotAt
            g.live.refresh()
            withTimeoutOrNull(12_000) {
                g.live.state.drop(1).first { it.snapshotAt != at || it.link == Link.OFFLINE || it.error != null }
            }
            local.update { it.copy(refreshing = false) }
        }
    }

    override fun tick() {
        launchBusy { report(g.ops.tick("manuale", false), "Tick eseguito") }
    }

    override fun pauseResume() {
        launchBusy {
            if (ui.value.snapshot?.stopped == true) {
                report(g.ops.mutate(Op.RESUME, "Catena ripresa") { it.resume() }, "Catena ripresa")
            } else {
                report(g.ops.mutate(Op.PAUSE, "Catena in pausa") { it.pause() }, "Catena in pausa")
            }
        }
    }

    override fun kill() {
        launchBusy { report(g.ops.mutate(Op.KILL, "Job terminato") { it.kill() }, "Job terminato") }
    }

    override fun clearQueue() = enqueue(emptyList(), replace = true, startNow = false, ok = "Coda svuotata")

    override fun queueMove(from: Int, to: Int) {
        editor.edit { it.moved(from, to) }
        publishQueue()
    }

    override fun queueRemove(index: Int): JobEntry? {
        val removed = editor.items.getOrNull(index) ?: return null
        editor.edit { l -> l.toMutableList().also { it.removeAt(index) } }
        publishQueue()
        return removed
    }

    override fun queueUndo(index: Int, entry: JobEntry) {
        editor.edit { l -> l.toMutableList().also { it.add(index.coerceAtMost(it.size), entry) } }
        publishQueue()
    }

    /** Writes the edited queue (one rewrite per batch of edits); on failure it reverts to the cluster's queue. */
    override fun queueFlush() {
        var list = editor.takeWrite() ?: return
        // the app-wide scope: leaving the screen or the activity must not cancel the write
        g.scope.launch(Dispatchers.Main) {
            while (true) {
                val e = g.ops.mutate(Op.QUEUE, "Coda aggiornata (${list.size})") { it.rewriteQueue(list) }
                val remote = g.live.state.value.snapshot?.queue.orEmpty()
                if (e.ok) {
                    editor.written(remote)
                } else {
                    editor.failed(remote)
                    _events.tryEmit("Coda non aggiornata: ${e.message}")
                }
                publishQueue()
                list = editor.takeWrite() ?: break
            }
        }
    }

    private fun enqueue(entries: List<JobEntry>, replace: Boolean, startNow: Boolean, ok: String) {
        queueFlush()
        launchBusy {
            val e = g.ops.mutate(Op.QUEUE, ok) { h ->
                if (replace) {
                    val s = h.rewriteQueue(entries)
                    if (startNow) h.tick().also { check(!it.tickFailed) { "Tick fallito" } }.snapshot else s
                } else {
                    h.enqueue(entries, startNow).also { check(!it.tickFailed) { "Tick fallito" } }.snapshot
                }
            }
            report(e, ok)
        }
    }

    override fun addJob(cell: ConfigCell, type: String) {
        runCatching { buildEntry(type, cell.path, cell.name) }
            .onSuccess { enqueue(listOf(it), replace = false, startNow = false, ok = "Aggiunto in coda") }
            .onFailure { _events.tryEmit(it.message ?: "Job non valido") }
    }

    override fun addPresets(presets: List<Preset>, replace: Boolean, startNow: Boolean) {
        runCatching { presets.flatMap { resolveJobs(local.value.catalog, it.jobs) } }
            .onSuccess { enqueue(it, replace, startNow, if (replace) "Coda sostituita" else "Aggiunti in coda") }
            .onFailure { _events.tryEmit(it.message ?: "Preset non valido") }
    }

    override fun loadCatalog() {
        if (local.value.catalogLoading) return
        local.update { it.copy(catalogLoading = true) }
        viewModelScope.launch {
            val (cells, presets) = withContext(Dispatchers.IO) {
                val f = GithubFetcher()
                val tree = runCatching { f.tree(Seed.REPO, Seed.BRANCH) }.getOrNull()?.let(ConfigCatalog::fromTree)
                val yaml = runCatching { String(f.fetch(Seed.REPO, Seed.BRANCH, SyncManifest.PRESETS_PATH), Charsets.UTF_8) }.getOrNull()
                tree?.let { c -> g.stores.cache(CATALOG_CACHE, c.joinToString("\n") { it.path }) }
                yaml?.let { g.stores.cache(PRESETS_CACHE, it) }
                tree to yaml?.let { runCatching { parsePresets(it) }.getOrNull() }
            }
            local.update {
                it.copy(
                    catalog = cells ?: it.catalog,
                    presets = presets ?: it.presets,
                    catalogLoading = false,
                    catalogError = if (it.catalog.isEmpty() && cells == null) "Catalogo non disponibile" else null,
                )
            }
        }
    }

    override fun saveSchedules(list: List<Schedule>) {
        viewModelScope.launch(Dispatchers.IO) {
            g.stores.saveSchedules(list)
            g.scheduler.sync()
        }
    }

    override fun runSchedule(s: Schedule) {
        launchBusy { report(g.ops.run(s.action, s.name, auto = false), "${s.name}: eseguito") }
    }

    /** Called on resume: the owner may have changed it in the system dialog. */
    fun refreshBattery() {
        val app = getApplication<Application>()
        val ignoring = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        local.update { it.copy(batteryRestricted = !ignoring) }
    }

    override fun requestBatteryExemption() {
        val app = getApplication<Application>()
        app.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private val updater = Updater(app)

    override fun checkUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(update = it.update.copy(checking = true, error = null)) }
            val r = runCatching { Updates.latest(Seed.APP_REPO) }
            val rel = r.getOrNull()
            val newer = rel != null && Updates.isNewer(rel.version, BuildConfig.VERSION_NAME)
            local.update { it.copy(update = UpdateUi(release = rel, newer = newer, checked = true, error = r.exceptionOrNull()?.let { "Controllo non riuscito" })) }
            if (newer) _events.tryEmit("Nuova versione ${rel!!.version}: Altro > Aggiornamenti")
        }
    }

    override fun installUpdate() {
        val rel = local.value.update.release ?: return
        viewModelScope.launch(Dispatchers.IO) {
            local.update { it.copy(update = it.update.copy(progress = 0f, error = null)) }
            try {
                var last = -1
                val apk = updater.download(rel) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != last) { last = pct; local.update { it.copy(update = it.update.copy(progress = p)) } }
                }
                local.update { it.copy(update = it.update.copy(progress = null)) }
                if (!updater.install(apk)) _events.tryEmit("Consenti a ClusterControl di installare app, poi riprova")
            } catch (e: Exception) {
                local.update { it.copy(update = it.update.copy(progress = null, error = "Download non riuscito")) }
            }
        }
    }

    override fun resumeAuto() {
        viewModelScope.launch(Dispatchers.IO) {
            g.stores.updateState { it.copy(autoSuspended = false, suspendReason = null) }
            g.scheduler.sync()
        }
        g.live.refresh()
    }

    override fun checkScripts() {
        viewModelScope.launch {
            local.update { it.copy(scripts = it.scripts.copy(busy = true, result = null)) }
            val r = runCatching { g.ops.syncStatus() }
            local.update {
                it.copy(scripts = ScriptsUi(r.getOrNull() ?: it.scripts.status, false, r.exceptionOrNull()?.let { e -> classify(e).message }))
            }
        }
    }

    override fun updateScripts() {
        viewModelScope.launch {
            local.update { it.copy(scripts = it.scripts.copy(busy = true, result = null)) }
            val e = g.ops.sync("manuale", false)
            val items = runCatching { g.ops.syncStatus() }.getOrNull()
            local.update { it.copy(scripts = ScriptsUi(items ?: it.scripts.status, false, e.message)) }
        }
    }

    override fun testConnection() {
        if (local.value.conn.running) return
        viewModelScope.launch {
            local.update { it.copy(conn = ConnUi(running = true)) }
            g.ops.testConnection { steps -> local.update { it.copy(conn = ConnUi(steps, true)) } }
            local.update { it.copy(conn = it.conn.copy(running = false)) }
            g.live.refresh()
        }
    }

    override fun clearHistory() = g.stores.clearHistory()

    private companion object {
        const val CATALOG_CACHE = "catalog.txt"
        const val PRESETS_CACHE = "presets.yaml"
    }
}
