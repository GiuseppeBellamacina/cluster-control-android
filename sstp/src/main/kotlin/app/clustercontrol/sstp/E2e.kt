package app.clustercontrol.sstp

import app.clustercontrol.core.ClusterConfig
import app.clustercontrol.core.ClusterSession
import app.clustercontrol.core.HelperApi
import app.clustercontrol.core.GithubFetcher
import app.clustercontrol.core.HostKeys
import app.clustercontrol.core.SshRemoteStore
import app.clustercontrol.core.SyncEngine
import app.clustercontrol.core.Transport
import java.io.File
import java.net.Socket
import kotlin.system.exitProcess

/**
 * End-to-end check of the app's real path: JSch ssh to the cluster THROUGH the userspace SSTP tunnel, then the
 * READ-ONLY helper `status`. Env: VPN_HOST, VPN_USER, VPN_PASSWORD, T2G_SSH_HOST, T2G_SSH_USER; key file at /key.
 * Prints only counts, never secrets.
 */
fun main() {
    fun env(n: String) = System.getenv(n) ?: error("$n not set")
    val t0 = System.currentTimeMillis()
    val sstp = SstpTransport(env("VPN_HOST"), env("VPN_USER"), env("VPN_PASSWORD")) { name, detail ->
        println("[%5dms] %-14s %s".format(System.currentTimeMillis() - t0, name, detail))
    }
    val transport = object : Transport {
        override fun open(host: String, port: Int, timeoutMs: Int): Socket = sstp.openSocket(host, port)
    }
    val keys = object : HostKeys {
        override fun get(host: String): String? = null
        override fun put(host: String, fingerprint: String) = println("host key $host $fingerprint")
    }
    val cfg = ClusterConfig(env("T2G_SSH_HOST"), env("T2G_SSH_USER"), File("/key").readText())
    var code = 0
    try {
        ClusterSession.open(cfg, transport, keys, timeoutMs = 45_000).use { s ->
            println("ssh: authenticated, echo -> " + s.exec("echo ok").stdout.trim())
            val snap = HelperApi(s, "~/neuro_symbolic_t2g").status()
            println("helper status: active=${snap.active != null} queue=${snap.queue.size} stopped=${snap.stopped}")
            // read-only: compares the repo on GitHub with the cluster, uploads nothing
            val sync = SyncEngine(GithubFetcher(), "GiuseppeBellamacina/neuro_symbolic_t2g").status(SshRemoteStore(s, "neuro_symbolic_t2g"))
            println("sync: compared ${sync.checked} files, ${sync.changes.size} to update (${sync.changes.count { it.isNew }} new)")
            // why does each differ? (read-only: cat on the cluster vs the same file on GitHub)
            val fetcher = GithubFetcher()
            val repo = "GiuseppeBellamacina/neuro_symbolic_t2g"
            val kinds = sortedMapOf<String, MutableList<String>>()
            val samples = mutableListOf<String>()
            for (c in sync.changes) {
                if (c.isNew) { kinds.getOrPut("missing on the cluster") { mutableListOf() } += c.path; continue }
                val remote = s.exec("cat ~/neuro_symbolic_t2g/${c.path}").stdout
                val github = String(fetcher.fetch(repo, "main", c.path), Charsets.UTF_8)
                val lf = remote.replace("\r\n", "\n")
                val kind = when {
                    remote == github -> "identical (?)"
                    lf == github -> "line endings only (CRLF on the cluster)"
                    lf.trimEnd() == github.trimEnd() -> "trailing whitespace or final newline only"
                    else -> "content differs"
                }
                kinds.getOrPut(kind) { mutableListOf() } += c.path
                if (kind == "content differs" && samples.size < 6) {
                    val rl = lf.lines()
                    val gl = github.lines()
                    val i = (0 until maxOf(rl.size, gl.size)).firstOrNull { rl.getOrNull(it) != gl.getOrNull(it) } ?: 0
                    samples += "${c.path} line ${i + 1}: cluster=[${rl.getOrNull(i)?.take(70)}] github=[${gl.getOrNull(i)?.take(70)}] (lines ${rl.size} vs ${gl.size})"
                }
            }
            kinds.forEach { (k, v) -> println("$k: ${v.size}"); v.take(6).forEach { println("    $it") } }
            samples.forEach { println("DIFF $it") }
        }
    } catch (e: Exception) {
        println("FAILED: ${e::class.simpleName}: ${e.message?.take(200)}")
        code = 1
    }
    exitProcess(code)
}
