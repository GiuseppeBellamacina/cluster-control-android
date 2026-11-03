package app.clustercontrol.sstp

import java.net.InetAddress
import kotlin.system.exitProcess

/**
 * Lab CLI. Credentials come from VPN_HOST / VPN_USER / VPN_PASSWORD (never printed).
 * Args: --no-auth (stop before sending credentials), --pin <sha256>, --target host:port (default 151.97.253.110:22),
 * --no-tcp (stop after IPCP).
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val host = System.getenv("VPN_HOST") ?: error("VPN_HOST not set")
    val user = System.getenv("VPN_USER") ?: error("VPN_USER not set")
    val password = System.getenv("VPN_PASSWORD") ?: error("VPN_PASSWORD not set")
    val noAuth = "--no-auth" in args
    val (tHost, tPort) = (opt("--target") ?: "151.97.253.110:22").split(":").let { it[0] to it[1].toInt() }
    val t0 = System.currentTimeMillis()
    val tunnel = SstpTunnel(host, user, password, pinSha256 = opt("--pin"), authenticate = !noAuth) { name, detail ->
        println("[%5dms] %-14s %s".format(System.currentTimeMillis() - t0, name, detail))
    }
    var code = 0
    try {
        tunnel.connect()
        if (!noAuth && "--no-tcp" !in args) {
            val flow = tunnel.openTcp(InetAddress.getByName(tHost), tPort)
            val buf = ByteArray(256)
            val n = flow.read(buf, 0, buf.size, 8000)
            println("banner: " + String(buf, 0, maxOf(n, 0), Charsets.ISO_8859_1).trim())
            flow.close()
        }
    } catch (e: Exception) {
        println("FAILED: ${e.message}")
        code = 1
    } finally {
        tunnel.close()
    }
    exitProcess(code)
}
