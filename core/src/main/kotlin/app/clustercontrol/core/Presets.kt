package app.clustercontrol.core

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

data class JobSpec(val type: String, val config: String, val tag: String?, val mode: String?)

data class Preset(val id: String, val label: String, val description: String, val jobs: List<JobSpec>)

/** Parses remote/presets.yaml (the same file the TUI reads). */
fun parsePresets(yaml: String): List<Preset> {
    val root = Load(LoadSettings.builder().build()).loadFromString(yaml) as? Map<*, *> ?: return emptyList()
    return (root["presets"] as? List<*>).orEmpty().mapNotNull { p ->
        val m = p as? Map<*, *> ?: return@mapNotNull null
        val jobs = (m["jobs"] as? List<*>).orEmpty().mapNotNull { j ->
            val jm = j as? Map<*, *> ?: return@mapNotNull null
            JobSpec(
                type = jm["type"]?.toString() ?: return@mapNotNull null,
                config = jm["config"]?.toString() ?: return@mapNotNull null,
                tag = jm["tag"]?.toString(),
                mode = jm["mode"]?.toString(),
            )
        }
        Preset(
            id = m["id"]?.toString() ?: return@mapNotNull null,
            label = m["label"]?.toString() ?: m["id"].toString(),
            description = m["description"]?.toString().orEmpty().trim(),
            jobs = jobs,
        )
    }
}

/** Resolves job specs against the catalog into queue entries (default tag = cell name). */
fun resolveJobs(catalog: List<ConfigCell>, jobs: List<JobSpec>): List<JobEntry> = jobs.map {
    val cell = ConfigCatalog.resolve(catalog, it.config)
    buildEntry(it.type, cell.path, it.tag ?: cell.name, it.mode)
}
