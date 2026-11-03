package app.clustercontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clustercontrol.core.ConfigCell
import app.clustercontrol.core.ConfigCatalog
import app.clustercontrol.core.GithubFetcher
import app.clustercontrol.core.HelperApi
import app.clustercontrol.core.JobEntry
import app.clustercontrol.core.KeyGen
import app.clustercontrol.core.Point
import app.clustercontrol.core.Preset
import app.clustercontrol.core.Schedule
import app.clustercontrol.core.ScheduleAction
import app.clustercontrol.core.Snapshot
import app.clustercontrol.core.SyncItem
import app.clustercontrol.core.parsePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val settings: AppSettings,
    val state: AppState,
    val schedules: List<Schedule>,
    val history: List<HistoryEntry>,
    val busy: Boolean = false,
    val error: ClusterError? = null,
    val message: String? = null,
    val catalog: List<ConfigCell> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val catalogError: String? = null,
    val series: Map<String, List<Point>> = emptyMap(),
    val seriesTotal: Int? = null,
    val resultDirs: List<String> = emptyList(),
    val results: Map<String, String> = emptyMap(),
    val syncPlan: List<SyncItem>? = null,
    val labLines: List<String> = emptyList(),
) {
    val snapshot: Snapshot? get() = state.snapshot
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val g = ClusterApp.graph
    private val _ui = MutableStateFlow(UiState(g.secure.load(), g.stores.state(), g.stores.schedules(), g.stores.history()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        if (_ui.value.settings.configured) refresh()
    }

    private suspend fun reload() {
        val f = withContext(Dispatchers.IO) { UiState(g.secure.load(), g.stores.state(), g.stores.schedules(), g.stores.history()) }
        _ui.update { it.copy(settings = f.settings, state = f.state, schedules = f.schedules, history = f.history) }
    }

    fun messageShown() = _ui.update { it.copy(message = null) }

    /** Runs [block] off the main thread; a failure becomes the dashboard error (and may trip the circuit breaker). */
    private fun launchOp(ok: String? = null, block: () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val err = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull()?.let { g.ops.handleFailure(it) } }
            reload()
            _ui.update { it.copy(busy = false, error = err, message = err?.message ?: ok) }
        }
    }

    fun refresh() = launchOp { g.ops.refresh() }

    fun runAction(action: ScheduleAction, source: String = "manuale") = launchOp {
        val e = g.ops.runAction(action, source, auto = false)
        if (!e.ok) throw ActionFailed(e.message)
    }

    private fun mutate(ok: String, block: (HelperApi) -> Snapshot) = launchOp(ok) { g.ops.mutate(block) }

    fun pauseResume() = if (_ui.value.snapshot?.stopped == true) mutate("Catena ripresa") { it.resume() } else mutate("Catena in pausa") { it.pause() }

    fun kill() = mutate("Job terminato") { it.kill() }

    fun removeTag(tag: String) = mutate("Rimosso $tag") { it.removeTag(tag) }

    fun submit(entries: List<JobEntry>, replace: Boolean, startNow: Boolean) = mutate("Coda aggiornata") { h ->
        if (replace) {
            h.rewriteQueue(entries).let { if (startNow) h.tick().also { t -> check(!t.tickFailed) { "Tick fallito" } }.snapshot else it }
        } else {
            h.enqueue(entries, startNow).also { check(!it.tickFailed) { "Tick fallito" } }.snapshot
        }
    }

    fun loadCatalog() {
        val s = _ui.value.settings
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val f = GithubFetcher(s.githubToken.ifBlank { null })
                    val repo = s.repo.trim()
                    val branch = s.branch.trim()
                    ConfigCatalog.fromTree(f.tree(repo, branch)) to
                        parsePresets(String(f.fetch(repo, branch, "remote/presets.yaml"), Charsets.UTF_8))
                }
            }
            _ui.update {
                r.fold(
                    { (c, p) -> it.copy(catalog = c, presets = p, catalogError = null) },
                    { e -> it.copy(catalogError = "Catalogo GitHub non caricato: ${e.message}") },
                )
            }
        }
    }

    fun loadSeries(tag: String) = launchOp {
        val r = g.ops.withCluster { _, h -> h.timeseries(tag) }
        _ui.update { it.copy(series = r.series, seriesTotal = r.total) }
    }

    fun loadResultDirs() = launchOp {
        val dirs = g.ops.withCluster { _, h -> h.results("").keys.toList() }
        _ui.update { it.copy(resultDirs = dirs) }
    }

    fun loadResults(token: String) = launchOp {
        val r = g.ops.withCluster { _, h -> h.results(token) }
        _ui.update { it.copy(results = r) }
    }

    fun saveSchedules(list: List<Schedule>) {
        viewModelScope.launch(Dispatchers.IO) {
            g.stores.saveSchedules(list)
            g.scheduler.sync()
            reload()
        }
    }

    fun setMaster(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            g.stores.updateState { it.copy(master = on) }
            g.scheduler.sync()
            reload()
        }
    }

    fun resumeAuto() {
        viewModelScope.launch(Dispatchers.IO) {
            g.stores.updateState { it.copy(autoSuspended = false, suspendReason = null) }
            g.scheduler.sync()
            reload()
        }
    }

    fun saveSettings(s: AppSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { g.secure.save(s) }
            reload()
            _ui.update { it.copy(message = "Impostazioni salvate") }
        }
    }

    fun generateKey(base: AppSettings) {
        viewModelScope.launch {
            val k = withContext(Dispatchers.Default) { runCatching { KeyGen.generate("clustercontrol") } }
            k.onSuccess { saveSettings(base.copy(privateKey = it.privateKeyPem, publicKey = it.publicKeyLine, passphrase = "")) }
                .onFailure { e -> _ui.update { it.copy(message = "Generazione chiave fallita: ${e.message}") } }
        }
    }

    fun forgetHostKey() {
        viewModelScope.launch(Dispatchers.IO) {
            g.secure.forgetHostKey(_ui.value.settings.host.trim())
            _ui.update { it.copy(message = "Host key dimenticata") }
        }
    }

    fun planSync() = launchOp { _ui.update { it.copy(syncPlan = g.ops.syncPlan()) } }

    fun runLab() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, labLines = listOf("Avvio test...")) }
            withContext(Dispatchers.IO) { g.ops.lab { line -> _ui.update { it.copy(labLines = it.labLines + line) } } }
            reload()
            _ui.update { it.copy(busy = false) }
        }
    }
}
