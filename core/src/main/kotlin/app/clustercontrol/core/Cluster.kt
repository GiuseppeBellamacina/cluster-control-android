package app.clustercontrol.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** How bytes reach the cluster: the system network (DirectTransport) or an in-app tunnel (SSTP). */
interface Transport {
    fun open(host: String, port: Int, timeoutMs: Int): Socket
}

object DirectTransport : Transport {
    override fun open(host: String, port: Int, timeoutMs: Int): Socket =
        Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
}

/** Trust-on-first-use store for the cluster host key (the app persists it). */
interface HostKeys {
    fun get(host: String): String?
    fun put(host: String, fingerprint: String)
}

class HostKeyChangedException(host: String) : Exception("host key of $host changed")

data class ClusterConfig(
    val host: String,
    val user: String,
    val privateKeyPem: String,
    val passphrase: String? = null,
    val port: Int = 22,
    val remoteRoot: String = "~/neuro_symbolic_t2g",
)

class RemoteFile(val relPath: String, val bytes: ByteArray, val executable: Boolean)

data class ExecResult(val rc: Int, val stdout: String, val stderr: String)

/** One ssh connection, reused for every command of an action (tick, sync, ...). */
class ClusterSession private constructor(
    private val session: Session,
    private val timeoutMs: Int,
) : AutoCloseable {
    fun exec(command: String, timeoutMs: Int = this.timeoutMs): ExecResult {
        val ch = session.openChannel("exec") as ChannelExec
        val err = ByteArrayOutputStream()
        ch.setCommand(command)
        ch.setErrStream(err)
        val input = ch.inputStream
        ch.connect(timeoutMs)
        try {
            val out = input.readBytes()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(20)
            return ExecResult(ch.exitStatus, String(out, Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8))
        } finally {
            ch.disconnect()
        }
    }

    /** Uploads [bytes] to [relPath] (relative to the remote home); creates directories, sets the mode. */
    fun put(relPath: String, bytes: ByteArray, executable: Boolean = false) =
        putAll(listOf(RemoteFile(relPath, bytes, executable)))

    /** Many files over ONE sftp channel. */
    fun putAll(files: List<RemoteFile>) {
        if (files.isEmpty()) return
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(timeoutMs)
        try {
            val made = HashSet<String>()
            for (f in files) {
                f.relPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
                    .runningReduce { acc, s -> "$acc/$s" }
                    .forEach { dir -> if (made.add(dir)) runCatching { sftp.mkdir(dir) } } // exists already: fine
                sftp.put(ByteArrayInputStream(f.bytes), f.relPath)
                sftp.chmod(if (f.executable) 0b111_101_101 else 0b110_100_100, f.relPath)
            }
        } finally {
            sftp.disconnect()
        }
    }

    override fun close() = session.disconnect()

    companion object {
        /** [serverAliveIntervalMs] > 0 sends ssh keepalives, so a long-lived session notices a dead link. */
        fun open(
            cfg: ClusterConfig,
            transport: Transport,
            hostKeys: HostKeys,
            timeoutMs: Int = 30_000,
            serverAliveIntervalMs: Int = 0,
        ): ClusterSession {
            val jsch = JSch()
            jsch.addIdentity("cluster", cfg.privateKeyPem.toByteArray(), null, cfg.passphrase?.toByteArray())
            val s = jsch.getSession(cfg.user, cfg.host, cfg.port)
            s.setConfig("StrictHostKeyChecking", "no") // checked below, trust on first use
            s.setConfig("PreferredAuthentications", "publickey")
            s.setSocketFactory(object : SocketFactory {
                override fun createSocket(host: String, port: Int): Socket = transport.open(host, port, timeoutMs)
                override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            })
            s.timeout = timeoutMs
            if (serverAliveIntervalMs > 0) {
                s.serverAliveInterval = serverAliveIntervalMs
                s.serverAliveCountMax = 2
            }
            s.connect(timeoutMs)
            val fp = s.hostKey.getFingerPrint(jsch)
            val known = hostKeys.get(cfg.host)
            if (known == null) {
                hostKeys.put(cfg.host, fp)
            } else if (known != fp) {
                s.disconnect()
                throw HostKeyChangedException(cfg.host)
            }
            return ClusterSession(s, timeoutMs)
        }
    }
}

private fun shq(v: String) = "'" + v.replace("'", "'\\''") + "'"

class HelperMissingException : Exception("cluster_helper.sh not on the cluster: run a script sync")

class NoActiveJobException : Exception("no active job")

data class TickResult(val snapshot: Snapshot, val tickFailed: Boolean)

/** The cluster_helper.sh protocol, one ssh exec per call (the same subcommands the Render driver uses). */
class HelperApi(private val session: ClusterSession, remoteRoot: String = "~/neuro_symbolic_t2g") {
    private val helper = "$remoteRoot/cluster/cluster_helper.sh"

    private fun run(sub: String, arg: String? = null, timeoutMs: Int = 90_000): ExecResult {
        val cmd = "if [ -f $helper ]; then bash $helper $sub" + (arg?.let { " " + shq(it) } ?: "") +
            "; else echo ERR_HELPER_MISSING=1 >&2; exit 127; fi"
        val r = session.exec(cmd, timeoutMs)
        if (r.rc == 127 && "ERR_HELPER_MISSING" in r.stderr) throw HelperMissingException()
        return r
    }

    private fun snapshot(r: ExecResult): Snapshot {
        if (r.rc != 0 && "STATUS_OK=1" !in r.stdout) {
            throw ProtocolException("helper rc=${r.rc}: ${r.stderr.trim().take(200)}")
        }
        return parseSnapshot(r.stdout)
    }

    fun status() = snapshot(run("status"))

    fun monitor(lines: Int = 200) = snapshot(run("monitor $lines"))

    fun tick(): TickResult {
        val r = run("tick")
        return TickResult(snapshot(r), tickFailed = r.rc == 3 || "ERR_TICK=" in r.stderr)
    }

    fun enqueue(entries: List<JobEntry>, startNow: Boolean = false): TickResult {
        val content = entries.joinToString(ENTRY_SEP.toString()) { it.toLine() }
        val r = run(if (startNow) "start_batch" else "enqueue_batch", content)
        return TickResult(snapshot(r), tickFailed = r.rc == 3 || "ERR_TICK=" in r.stderr)
    }

    /** Replaces the whole queue (an empty list empties it). */
    fun rewriteQueue(entries: List<JobEntry>) =
        snapshot(run("rewrite_queue", entries.joinToString(ENTRY_SEP.toString()) { it.toLine() }))

    /** Removes every job with [tag]: rewrites the queue without them, like DELETE /jobs/{tag}. */
    fun removeTag(tag: String): Snapshot {
        val keep = status().queue.filter { it.tag != tag }
        return rewriteQueue(keep)
    }

    fun pause() = snapshot(run("pause"))

    fun resume() = snapshot(run("resume"))

    fun kill(): Snapshot {
        val r = run("scancel")
        if (r.rc == 1 && "ERR_NO_ACTIVE_JOB" in r.stderr) throw NoActiveJobException()
        return snapshot(r)
    }

    data class SeriesResult(val match: Boolean, val total: Int?, val series: Map<String, List<Point>>)

    fun timeseries(tag: String): SeriesResult {
        val kv = parseKv(run("timeseries", tag).stdout)
        val log = kv["TS_LOG_B64"]?.takeIf { it.isNotBlank() }
            ?.let { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }.orEmpty()
        return SeriesResult(kv["TS_MATCH"] == "1", kv["TS_TOTAL_STEPS"]?.toIntOrNull(), parseSeries(log))
    }

    /** Empty [token] lists the available result dirs; otherwise the eval json (raw text) of every run. */
    fun results(token: String): Map<String, String> {
        val kv = parseKv(run("results", token).stdout)
        if (token.isEmpty()) {
            return kv["RESULTS_DIRS"].orEmpty().split(ENTRY_SEP).filter { it.isNotEmpty() }.associateWith { "" }
        }
        val n = kv["RESULTS_COUNT"]?.toIntOrNull() ?: 0
        return (1..n).mapNotNull { i ->
            val id = kv["RUN_ID_$i"] ?: return@mapNotNull null
            val b64 = kv["RUN_B64_$i"] ?: return@mapNotNull null
            id to String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
        }.toMap()
    }
}
