package app.clustercontrol.core

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the scripts come from (GitHub) - an interface so tests do not need the network. */
interface SourceFetcher {
    /** Every file path of the repo tree. */
    fun tree(repo: String, branch: String): List<String>

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

    override fun tree(repo: String, branch: String): List<String> {
        val body = String(get("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1"), Charsets.UTF_8)
        val root = Json.parseToJsonElement(body).jsonObject
        return root["tree"]!!.jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "blob" }
            .map { it["path"]!!.jsonPrimitive.content }
    }

    override fun fetch(repo: String, branch: String, path: String): ByteArray =
        get("https://raw.githubusercontent.com/$repo/$branch/$path")
}

/** A script to keep identical on the cluster: repo path -> path relative to the project root on the cluster. */
data class SyncItem(val repoPath: String, val remotePath: String, val executable: Boolean) {
    val name: String get() = repoPath.substringAfterLast('/')
}

/** The cluster utility scripts (the .sh files of cluster/) and nothing else: src/ and configs travel with the repo's pre-push hook. */
object SyncManifest {
    const val PRESETS_PATH = "cluster/presets.yaml"

    fun items(tree: List<String>): List<SyncItem> = tree
        .filter { it.startsWith("cluster/") && it.count { c -> c == '/' } == 1 && it.endsWith(".sh") }
        .sorted()
        .map { SyncItem(it, it, true) }
}

/** The cluster's copy of the project: hashes + uploads (an interface so tests need no sshd). */
interface RemoteStore {
    /** sha256 of each remote file (null = missing). */
    fun hashes(relPaths: List<String>): Map<String, String?>

    fun put(relPath: String, bytes: ByteArray, executable: Boolean)
}

class SshRemoteStore(private val session: ClusterSession, private val root: String = "neuro_symbolic_t2g") : RemoteStore {
    override fun hashes(relPaths: List<String>): Map<String, String?> {
        if (relPaths.isEmpty()) return emptyMap()
        val quoted = relPaths.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val r = session.exec("cd ~/$root 2>/dev/null && sha256sum -- $quoted 2>/dev/null; true")
        val found = r.stdout.lineSequence().mapNotNull { l ->
            val i = l.indexOf("  ")
            if (i == 64) l.substring(66) to l.substring(0, 64) else null
        }.toMap()
        return relPaths.associateWith { found[it] }
    }

    override fun put(relPath: String, bytes: ByteArray, executable: Boolean) =
        session.put("$root/$relPath", bytes, executable)
}

data class ScriptStatus(val name: String, val upToDate: Boolean)

data class SyncReport(val checked: Int, val updated: List<String>, val errors: List<String>)

class SyncEngine(
    private val fetcher: SourceFetcher,
    private val repo: String,
    private val branch: String = "main",
) {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Every cluster script with whether the cluster already has the same bytes. */
    fun status(remote: RemoteStore): List<ScriptStatus> {
        val items = SyncManifest.items(fetcher.tree(repo, branch))
        val remoteHashes = remote.hashes(items.map { it.remotePath })
        return items.map { ScriptStatus(it.name, sha(fetcher.fetch(repo, branch, it.repoPath)) == remoteHashes[it.remotePath]) }
    }

    /** Uploads only what differs from the cluster. Idempotent: a second run reports nothing updated. */
    fun apply(remote: RemoteStore): SyncReport {
        val items = SyncManifest.items(fetcher.tree(repo, branch))
        val remoteHashes = remote.hashes(items.map { it.remotePath })
        val updated = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (item in items) {
            try {
                val bytes = fetcher.fetch(repo, branch, item.repoPath)
                if (sha(bytes) == remoteHashes[item.remotePath]) continue
                remote.put(item.remotePath, bytes, item.executable)
                updated += item.name
            } catch (e: Exception) {
                errors += "${item.name}: ${e.message}"
            }
        }
        return SyncReport(items.size, updated, errors)
    }
}
