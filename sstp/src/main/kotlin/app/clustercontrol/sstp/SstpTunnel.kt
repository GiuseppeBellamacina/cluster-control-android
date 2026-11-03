package app.clustercontrol.sstp

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * SSTP client: TLS to [host]:[port], SSTP handshake, PPP (MS-CHAPv2 + IPCP). Afterwards [openTcp] gives one TCP flow
 * through the tunnel. With [authenticate] = false the handshake stops when the server's CHAP challenge arrives,
 * before any credential is sent. [pinSha256] pins the server leaf certificate instead of using the JVM trust store.
 * Secrets are never passed to [onStage].
 */
class SstpTunnel(
    private val host: String,
    private val user: String,
    private val password: String,
    private val port: Int = 443,
    private val pinSha256: String? = null,
    private val authenticate: Boolean = true,
    private val onStage: (name: String, detail: String) -> Unit = { _, _ -> },
) : Closeable {
    private val ready = CompletableFuture<Unit>()
    private val writeLock = Any()
    private var out: OutputStream? = null
    private var raw: Closeable? = null
    private var certDer = ByteArray(0)
    private var hashProto = Sstp.HASH_SHA256
    private var nonce = ByteArray(32)
    private var stageName = "init"
    @Volatile private var closed = false
    @Volatile private var tcp: TcpFlow? = null
    private lateinit var ppp: Ppp
    private var timer: ScheduledExecutorService? = null

    private fun stage(name: String, detail: String) {
        stageName = name
        onStage(name, detail)
    }

    fun connect() {
        val tls = openTls()
        raw = tls
        attach(tls.inputStream, tls.outputStream, tls.session.peerCertificates[0].encoded)
        await()
    }

    /** Waits for PPP/IPCP to come up (or the CHAP challenge when [authenticate] is false). */
    internal fun await() {
        try {
            ready.get(30, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            close()
            throw SstpException("timeout at stage '$stageName'")
        } catch (e: java.util.concurrent.ExecutionException) {
            close()
            throw (e.cause as? SstpException) ?: SstpException(e.cause?.message ?: "failed at stage '$stageName'")
        }
    }

    /** HTTP upgrade + Call Connect Request over an established TLS stream; then runs the reader thread. */
    internal fun attach(input: InputStream, output: OutputStream, serverCertDer: ByteArray) {
        certDer = serverCertDer
        out = output
        val inp = BufferedInputStream(input)
        val req = "SSTP_DUPLEX_POST $SSTP_ENDPOINT_PATH HTTP/1.1\r\nHost: $host\r\n" +
            "SSTPCORRELATIONID: {${UUID.randomUUID().toString().uppercase()}}\r\n" +
            "Content-Length: 18446744073709551615\r\n\r\n"
        output.write(req.toByteArray())
        output.flush()
        val status = readHeaders(inp)
        if (!status.contains(" 200")) throw SstpException("unexpected HTTP response: $status")
        stage("http", status)

        ppp = Ppp(user, password, authenticate, { sendRaw(Sstp.packet(false, it)) }, object : PppEvents {
            override fun stage(name: String, detail: String) = this@SstpTunnel.stage(name, detail)
            override fun authenticated(hlak: ByteArray) {
                sendRaw(Sstp.callConnected(hashProto, nonce, certDer, hlak))
                stage("crypto-binding", "Call Connected sent (hash ${Sstp.hashAlg(hashProto)})")
            }
            override fun ready() { ready.complete(Unit) }
            override fun failed(reason: String) = fail(reason)
        })
        sendRaw(Sstp.connectRequest())
        stage("sstp-connect", "Call Connect Request sent")
        timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "sstp-timer").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { ppp.tick(); tcp?.tick() } }, 200, 200, TimeUnit.MILLISECONDS)
        }
        Thread({ readLoop(inp) }, "sstp-reader").apply { isDaemon = true }.start()
    }

    private fun readHeaders(inp: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) throw SstpException("connection closed during HTTP handshake")
            sb.append(b.toChar())
            if (sb.length > 8192) throw SstpException("HTTP header too long")
        }
        return sb.lineSequence().first()
    }

    private fun readLoop(inp: InputStream) {
        try {
            val hdr = ByteArray(4)
            while (!closed) {
                inp.readNBytes(hdr, 0, 4).let { if (it < 4) throw SstpException("connection closed by server") }
                if (hdr.u8(0) != 0x10) throw SstpException("bad SSTP version 0x%02x".format(hdr.u8(0)))
                val len = ((hdr.u8(2) and 0x0F) shl 8) or hdr.u8(3)
                val body = inp.readNBytes(len - 4)
                if (body.size < len - 4) throw SstpException("connection closed by server")
                if (hdr.u8(1) and 1 != 0) control(Sstp.parseControl(body))
                else ppp.onFrame(body)?.let { tcp?.onIp(it) }
            }
        } catch (e: Exception) {
            if (!closed) fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun control(c: Sstp.Control) {
        when (c.type) {
            Sstp.CONNECT_ACK -> {
                val req = c.attrs[Sstp.ATTR_CRYPTO_BINDING_REQ]
                if (req == null || req.size < 36) throw SstpException("Call Connect Ack without crypto binding request")
                val mask = req.u8(3)
                hashProto = if (mask and Sstp.HASH_SHA256 != 0) Sstp.HASH_SHA256 else Sstp.HASH_SHA1
                nonce = req.copyOfRange(4, 36)
                stage("sstp-ack", "Call Connect Ack, hash bitmask 0x%02x, chose %s".format(mask, Sstp.hashAlg(hashProto)))
                ppp.start()
            }
            Sstp.CONNECT_NAK -> throw SstpException("server sent Call Connect Nak")
            Sstp.ABORT -> {
                val s = c.attrs[Sstp.ATTR_STATUS_INFO]
                val d = if (s != null && s.size >= 8) "attribute %d status 0x%08x".format(s.u8(3), s.u32(4)) else "no status info"
                throw SstpException("server sent Call Abort ($d) at stage '$stageName'")
            }
            Sstp.DISCONNECT -> {
                sendRaw(Sstp.control(Sstp.DISCONNECT_ACK))
                throw SstpException("server sent Call Disconnect at stage '$stageName'")
            }
            Sstp.DISCONNECT_ACK -> throw SstpException("server acknowledged disconnect")
            Sstp.ECHO_REQUEST -> sendRaw(Sstp.control(Sstp.ECHO_RESPONSE))
            else -> stage("sstp", "unhandled control message type ${c.type}")
        }
    }

    private fun sendRaw(pkt: ByteArray) = synchronized(writeLock) {
        val o = out ?: throw SstpException("not connected")
        o.write(pkt)
        o.flush()
    }

    private fun fail(reason: String) {
        ready.completeExceptionally(SstpException(reason))
        tcp?.fail(reason)
        if (!closed) onStage("error", reason)
        close()
    }

    /** Opens the single TCP flow; [addr] must be IPv4 and already resolved. */
    internal fun openTcp(addr: InetAddress, port: Int): TcpFlow {
        if (addr !is Inet4Address) throw SstpException("only IPv4 targets are supported")
        check(ready.isDone && !ready.isCompletedExceptionally) { "tunnel not connected" }
        val flow = TcpFlow(ppp.localIp, addr.address, port, { ppp.sendIp(it) }, { stage("tcp", it) })
        tcp = flow
        flow.connect(15_000)
        stage("tcp", "connected to ${addr.hostAddress}:$port")
        return flow
    }

    override fun close() {
        if (closed) return
        closed = true
        timer?.shutdownNow()
        runCatching { sendRaw(Sstp.control(Sstp.DISCONNECT)) }
        runCatching { raw?.close() }
        runCatching { out?.close() }
    }

    private class Trust(val delegate: X509TrustManager, val pin: String?) : X509TrustManager {
        var chain: Array<X509Certificate>? = null
        override fun checkClientTrusted(c: Array<X509Certificate>, t: String) = throw CertificateException("client auth unsupported")
        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
        override fun checkServerTrusted(c: Array<X509Certificate>, t: String) {
            chain = c
            if (pin == null) delegate.checkServerTrusted(c, t)
            else if (fingerprint(c[0]) != pin) throw CertificateException("server certificate does not match the pinned SHA-256")
        }
    }

    private fun openTls(): SSLSocket {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(null as java.security.KeyStore?) }
        val trust = Trust(tmf.trustManagers.filterIsInstance<X509TrustManager>().first(), pinSha256?.let { normFp(it) })
        val ctx = SSLContext.getInstance("TLS").also { it.init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        stage("tcp-connect", "connecting to $host:$port")
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), 10_000)
        val ssl = ctx.socketFactory.createSocket(sock, host, port, true) as SSLSocket
        if (pinSha256 == null) ssl.sslParameters = ssl.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
        try {
            ssl.soTimeout = 30_000
            ssl.startHandshake()
            ssl.soTimeout = 0
        } catch (e: SSLHandshakeException) {
            ssl.close()
            val chain = trust.chain?.joinToString(" | ") { describe(it) } ?: "none received"
            throw SstpException("TLS handshake failed: ${e.message}. Server chain: $chain. Pass the leaf SHA-256 as pin to trust it explicitly.")
        }
        val leaf = ssl.session.peerCertificates[0] as X509Certificate
        stage("tls", "${ssl.session.protocol} ${ssl.session.cipherSuite}; ${describe(leaf)}")
        return ssl
    }

    private companion object {
        fun fingerprint(c: X509Certificate) = digest("SHA-256", c.encoded).hex()
        fun normFp(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        fun describe(c: X509Certificate) = "subject=${c.subjectX500Principal} issuer=${c.issuerX500Principal} sha256=${fingerprint(c)}"
    }
}
