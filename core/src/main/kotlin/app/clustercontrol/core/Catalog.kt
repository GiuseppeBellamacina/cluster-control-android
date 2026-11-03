package app.clustercontrol.core

data class ConfigCell(val name: String, val path: String)

/** Config cells of the experiments/configs tree and their job tags (mirror of src/utils/run_paths.py::cell_tag). */
object ConfigCatalog {
    private const val PREFIX = "experiments/configs/"
    private val DATASETS = setOf("aslg-pc12", "phoenix-2014t", "wos-46985", "conll-2003")
    private const val DEFAULT_DATASET = "aslg-pc12"
    private const val DEFAULT_MODEL = "qwen25-05b"

    fun cellTag(cell: String): String {
        var parts = cell.trim('/').split('/').filter { it.isNotEmpty() }
        val dataset = if (parts.firstOrNull() in DATASETS) parts.first().also { parts = parts.drop(1) } else DEFAULT_DATASET
        val model = parts.firstOrNull().orEmpty()
        val rest = parts.drop(1).joinToString("/")
        val prefix = if (model == DEFAULT_MODEL) dataset else "$dataset-$model"
        return "$prefix-" + rest.replace('/', '-').replace('_', '-')
    }

    /** Every yaml below experiments/configs except the shared `base*.yaml` files. */
    fun fromTree(paths: List<String>): List<ConfigCell> = paths
        .filter { it.startsWith(PREFIX) && it.endsWith(".yaml") && !it.substringAfterLast('/').startsWith("base") }
        .sorted()
        .map { ConfigCell(cellTag(it.removePrefix(PREFIX).removeSuffix(".yaml")), it) }

    /** Name, full path, or unique path suffix (as remote/app.py::resolve_config). */
    fun resolve(catalog: List<ConfigCell>, ref: String): ConfigCell {
        catalog.firstOrNull { it.name == ref || it.path == ref }?.let { return it }
        val wanted = ref.removePrefix("./")
        val matches = catalog.filter { it.path.endsWith(wanted) }
            .ifEmpty { catalog.filter { it.path.substringAfterLast('/') == wanted.substringAfterLast('/') } }
        require(matches.isNotEmpty()) { "unknown config: $ref" }
        require(matches.size == 1) { "ambiguous config $ref: ${matches.joinToString { it.name }}" }
        return matches.first()
    }
}
