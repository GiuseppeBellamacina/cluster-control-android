package app.clustercontrol.sstp

import app.clustercontrol.core.ClusterConfig
import app.clustercontrol.core.ClusterSession
import app.clustercontrol.core.HelperApi
import app.clustercontrol.core.HostKeys
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
            val snap = HelperApi(s).status()
            println("helper status: active=${snap.active != null} queue=${snap.queue.size} stopped=${snap.stopped}")
        }
    } catch (e: Exception) {
        println("FAILED: ${e::class.simpleName}: ${e.message?.take(200)}")
        code = 1
    }
    exitProcess(code)
}
