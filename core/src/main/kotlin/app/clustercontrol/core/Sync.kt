package app.clustercontrol.core

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One file of the repo tree; [sha] is the git blob id (SHA-1 over "blob <size>\u0000" + content). */
data class TreeEntry(val path: String, val sha: String, val size: Long)

/** Git's id of a content. The cluster computes the same with plain sha1sum, so no file has to be downloaded to compare. */
fun gitBlobSha(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-1")
    md.update("blob ${bytes.size}\u0000".toByteArray())
    md.update(bytes)
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Where the project comes from (GitHub) - an interface so tests do not need the network. */
interface SourceFetcher {
    fun entries(repo: String, branch: String): List<TreeEntry>

    /** Every file path of the repo tree. */
    fun tree(repo: String, branch: String): List<String> = entries(repo, branch).map { it.path }

    fun fetch(repo: String, branch: String, path: String): ByteArray
}

class GithubFetcher(private val token: String? = null) : SourceFetcher {
    private fun get(url: String, accept: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        accept?.let { c.setRequestProperty("Accept", it) }
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.readBytes()
        } finally {
            c.disconnect()
        }
    }

    override fun entries(repo: String, branch: String): List<TreeEntry> {
        val body = String(get("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1"), Charsets.UTF_8)
        val root = Json.parseToJsonElement(body).jsonObject
        if (root["truncated"]?.jsonPrimitive?.content == "true") throw java.io.IOException("GitHub truncated the repo tree")
        return root["tree"]!!.jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "blob" }
            .map {
                TreeEntry(
                    it["path"]!!.jsonPrimitive.content,
                    it["sha"]!!.jsonPrimitive.content,
                    it["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                )
            }
    }

    override fun fetch(repo: String, branch: String, path: String): ByteArray =
        get("https://raw.githubusercontent.com/$repo/$branch/$path")
}

/** A file to keep identical on the cluster (same path in the repo and under the project root there). */
data class SyncItem(val path: String, val sha: String, val executable: Boolean) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * What the cluster needs, as sync_cluster.ps1 uploads it: the .py files of src, the .sh files of cluster, the .yaml
 * files of experiments/configs, main.py and pyproject.toml. An allow-list on purpose: the repo also tracks experiment results and figures, and
 * those must never travel to the cluster. cluster/presets.yaml is app-only data.
 */
object SyncManifest {
    const val PRESETS_PATH = "cluster/presets.yaml"
    private const val MAX_BYTES = 512 * 1024L

    private fun wanted(e: TreeEntry): Boolean {
        val p = e.path
        if (e.size > MAX_BYTES || "/__pycache__/" in p) return false
        return when {
            p == "main.py" || p == "pyproject.toml" -> true
            p.startsWith("src/") -> p.endsWith(".py")
            p.startsWith("cluster/") -> p.count { it == '/' } == 1 && p.endsWith(".sh")
            p.startsWith("experiments/configs/") -> p.endsWith(".yaml")
            else -> false
        }
    }

    /** The code a running job may still import: changing it mid-run can break that job. */
    fun isCode(path: String) = path.startsWith("src/") || path == "main.py" || path == "pyproject.toml"

    fun items(entries: List<TreeEntry>): List<SyncItem> =
        entries.filter(::wanted).sortedBy { it.path }.map { SyncItem(it.path, it.sha, it.path.endsWith(".sh")) }
}

/** The cluster's copy of the project (an interface so tests need no sshd). */
interface RemoteStore {
    /** git blob id of each remote file (null = missing). */
    fun blobShas(relPaths: List<String>): Map<String, String?>

    fun putAll(files: List<RemoteFile>)
}

class SshRemoteStore(private val session: ClusterSession, private val root: String = "neuro_symbolic_t2g") : RemoteStore {
    private fun shq(v: String) = "'" + v.replace("'", "'\\''") + "'"

    // git's blob id from plain shell tools: sha1("blob <size>\0" + content); the whole check is ONE command
    private val script = """
        cd ~/$root 2>/dev/null || exit 0
        for f in "${'$'}@"; do
          [ -f "${'$'}f" ] || continue
          printf '%s  %s\n' "${'$'}({ printf 'blob %s\0' "${'$'}(wc -c < "${'$'}f")"; cat "${'$'}f"; } | sha1sum | cut -d' ' -f1)" "${'$'}f"
        done
    """.trimIndent()

    override fun blobShas(relPaths: List<String>): Map<String, String?> {
        if (relPaths.isEmpty()) return emptyMap()
        val r = session.exec("bash -c ${shq(script)} _ " + relPaths.joinToString(" ") { shq(it) })
        val found = r.stdout.lineSequence().mapNotNull { l ->
            if (l.length > 42 && l[40] == ' ' && l[41] == ' ') l.substring(42) to l.substring(0, 40) else null
        }.toMap()
        return relPaths.associateWith { found[it] }
    }

    override fun putAll(files: List<RemoteFile>) =
        session.putAll(files.map { RemoteFile("$root/${it.relPath}", it.bytes, it.executable) })
}

data class SyncChange(val path: String, val isNew: Boolean)

/** Only what differs; [checked] is how many files were compared. */
data class SyncStatus(val checked: Int, val changes: List<SyncChange>) {
    val upToDate: Boolean get() = changes.isEmpty()
}

data class SyncReport(val checked: Int, val updated: List<String>, val errors: List<String>, val skipped: List<String> = emptyList())

class SyncEngine(
    private val fetcher: SourceFetcher,
    private val repo: String,
    private val branch: String = "main",
) {
    private fun pending(remote: RemoteStore): Pair<Int, List<Pair<SyncItem, Boolean>>> {
        val items = SyncManifest.items(fetcher.entries(repo, branch))
        val shas = remote.blobShas(items.map { it.path })
        return items.size to items.filter { shas[it.path] != it.sha }.map { it to (shas[it.path] == null) }
    }

    /** One tree request to GitHub and one command on the cluster; nothing is downloaded. */
    fun status(remote: RemoteStore): SyncStatus =
        pending(remote).let { (n, p) -> SyncStatus(n, p.map { (item, isNew) -> SyncChange(item.path, isNew) }) }

    /** Downloads and uploads only what differs. Idempotent: a second run has nothing to do. Paths matching [skip] are left alone. */
    fun apply(remote: RemoteStore, skip: (String) -> Boolean = { false }): SyncReport {
        val (checked, all) = pending(remote)
        val (skipped, pending) = all.partition { skip(it.first.path) }
        val files = mutableListOf<RemoteFile>()
        val errors = mutableListOf<String>()
        for ((item, _) in pending) {
            try {
                val bytes = fetcher.fetch(repo, branch, item.path)
                if (gitBlobSha(bytes) != item.sha) errors += "${item.path}: changed while downloading" else files += RemoteFile(item.path, bytes, item.executable)
            } catch (e: Exception) {
                errors += "${item.path}: ${e.message}"
            }
        }
        return try {
            remote.putAll(files)
            SyncReport(checked, files.map { it.relPath }, errors, skipped.map { it.first.path })
        } catch (e: Exception) {
            SyncReport(checked, emptyList(), errors + "upload: ${e.message}", skipped.map { it.first.path })
        }
    }
}
