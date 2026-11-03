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

/** A file to keep identical on the cluster: repo path -> path relative to the project root on the cluster. */
data class SyncItem(val repoPath: String, val remotePath: String, val executable: Boolean)

data class SyncOptions(val includeSrc: Boolean = false)

object SyncManifest {
    /** Everything the chain needs on the cluster: scripts, the helper, and the experiment configs. */
    fun items(tree: List<String>, options: SyncOptions = SyncOptions()): List<SyncItem> {
        val out = LinkedHashMap<String, SyncItem>()
        for (p in tree.sorted()) {
            when {
                p.startsWith("cluster/") && p.count { it == '/' } == 1 && p.endsWith(".sh") ->
                    out[p] = SyncItem(p, p, true)
                p.startsWith("experiments/configs/") && p.endsWith(".yaml") -> out[p] = SyncItem(p, p, false)
                options.includeSrc && p.startsWith("src/") && p.endsWith(".py") -> out[p] = SyncItem(p, p, false)
            }
        }
        // the helper lives in remote/ in the repo but runs from cluster/ on the cluster
        if ("remote/cluster_helper.sh" in tree) {
            out["remote/cluster_helper.sh"] =
                SyncItem("remote/cluster_helper.sh", "cluster/cluster_helper.sh", true)
        }
        return out.values.toList()
    }
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

data class SyncReport(val checked: Int, val updated: List<String>, val errors: List<String>)

class SyncEngine(
    private val fetcher: SourceFetcher,
    private val repo: String,
    private val branch: String = "main",
) {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun plan(remote: RemoteStore, options: SyncOptions = SyncOptions()): List<SyncItem> =
        SyncManifest.items(fetcher.tree(repo, branch), options).let { items ->
            val bytesByItem = items.associateWith { fetcher.fetch(repo, branch, it.repoPath) }
            val remoteHashes = remote.hashes(items.map { it.remotePath })
            items.filter { sha(bytesByItem.getValue(it)) != remoteHashes[it.remotePath] }
        }

    /** Uploads only what differs from the cluster. Idempotent: a second run reports nothing updated. */
    fun apply(remote: RemoteStore, options: SyncOptions = SyncOptions()): SyncReport {
        val items = SyncManifest.items(fetcher.tree(repo, branch), options)
        val remoteHashes = remote.hashes(items.map { it.remotePath })
        val updated = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (item in items) {
            try {
                val bytes = fetcher.fetch(repo, branch, item.repoPath)
                if (sha(bytes) == remoteHashes[item.remotePath]) continue
                remote.put(item.remotePath, bytes, item.executable)
                updated += item.remotePath
            } catch (e: Exception) {
                errors += "${item.remotePath}: ${e.message}"
            }
        }
        return SyncReport(items.size, updated, errors)
    }
}
