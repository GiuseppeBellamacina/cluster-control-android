package app.clustercontrol.sstp

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-process SSTP/PPP/TCP server (no TLS) exercising the whole client stack. */
class FakeServer(private val password: String, private val corruptAuth: Boolean = false) {
    val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val cert = "fake-cert-der".toByteArray()
    val received = StringBuffer()
    val trace = StringBuffer()
    @Volatile var bindingOk = false
    @Volatile var sawFin = false
    private val nonce = ByteArray(32) { (it * 3).toByte() }
    private val clientIp = byteArrayOf(10, 8, 0, 2)
    private val serverIp = byteArrayOf(10, 8, 0, 1)
    private lateinit var out: OutputStream
    private var hlak = ByteArray(0)
    private val chapChallenge = ByteArray(16) { (it + 1).toByte() }
    private var srvSeq = 1000
    private var cliNext = 0
    private var target = serverIp

    fun start() = thread(isDaemon = true) { srv.accept().use { run(it) } }

    @Synchronized
    private fun send(pkt: ByteArray) {
        out.write(pkt)
        out.flush()
    }

    private fun ppp(proto: Int, payload: ByteArray) =
        send(Sstp.packet(false, byteArrayOf(0xFF.toByte(), 3) + be16(proto) + payload))

    private fun cp(proto: Int, code: Int, id: Int, data: ByteArray) =
        ppp(proto, byteArrayOf(code.toByte(), id.toByte()) + be16(data.size + 4) + data)

    private fun run(s: Socket) {
        val inp = s.getInputStream().buffered()
        out = s.getOutputStream()
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
        assertTrue(sb.startsWith("SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1"))
        assertTrue(sb.contains("SSTPCORRELATIONID: {"))
        out.write("HTTP/1.1 200 OK\r\nContent-Length: 18446744073709551615\r\n\r\n".toByteArray())
        val hdr = ByteArray(4)
        while (inp.readNBytes(hdr, 0, 4) == 4) {
            val len = ((hdr.u8(2) and 15) shl 8) or hdr.u8(3)
            val body = inp.readNBytes(len - 4)
            try { if (hdr.u8(1) and 1 != 0) control(hdr + body) else frame(body) } catch (e: Throwable) { java.io.File("/work/dbg.txt").appendText(e.stackTraceToString()) }
        }
    }

    private fun control(full: ByteArray) {
        val c = Sstp.parseControl(full.copyOfRange(4, full.size))
        when (c.type) {
            Sstp.CONNECT_REQUEST -> {
                send(Sstp.control(Sstp.CONNECT_ACK, listOf(Sstp.ATTR_CRYPTO_BINDING_REQ to (byteArrayOf(0, 0, 0, 3) + nonce))))
                cp(0xC021, 1, 1, byteArrayOf(1, 4) + be16(1500) + byteArrayOf(3, 5, 0xC2.toByte(), 0x23, 0x81.toByte(), 5, 6, 1, 2, 3, 4))
            }
            Sstp.CONNECTED -> {
                val v = c.attrs[Sstp.ATTR_CRYPTO_BINDING]!!
                val proto = v.u8(3)
                val mac = full.copyOfRange(80, 112)
                val zeroed = full.copyOf().also { ByteArray(32).copyInto(it, 80) }
                val alg = if (proto == 2) "HmacSHA256" else "HmacSHA1"
                val want = hmac(alg, Sstp.cmk(hlak, proto), zeroed).copyOf(32)
                bindingOk = proto == 2 && mac.contentEquals(want) &&
                    v.copyOfRange(36, 68).contentEquals(digest("SHA-256", cert)) && v.copyOfRange(4, 36).contentEquals(nonce)
            }
            Sstp.ECHO_REQUEST -> send(Sstp.control(Sstp.ECHO_RESPONSE))
        }
    }

    private fun frame(f: ByteArray) {
        trace.append(" p%04x".format(f.u16(2)))
        val p = f.copyOfRange(4, f.size)
        when (f.u16(2)) {
            0xC021 -> when (p[0].toInt()) {
                1 -> cp(0xC021, 2, p.u8(1), p.copyOfRange(4, p.size))
                2 -> cp(0xC223, 1, 9, byteArrayOf(16) + chapChallenge + "fake".toByteArray())
            }
            0xC223 -> chapResponse(p)
            0x8021 -> ipcp(p)
            0x0021 -> ip(p)
        }
    }

    private fun chapResponse(p: ByteArray) {
        val value = p.copyOfRange(5, 5 + 49)
        val user = String(p, 54, p.size - 54)
        val ch = MsChapV2.challengeHash(value.copyOfRange(0, 16), chapChallenge, user)
        val ntHash = MsChapV2.ntHash(password)
        val nt = MsChapV2.ntResponse(ch, ntHash)
        if (!nt.contentEquals(value.copyOfRange(24, 48))) {
            cp(0xC223, 4, p.u8(1), "E=691 R=0 V=3".toByteArray())
            return
        }
        hlak = MsChapV2.hlak(ntHash, nt)
        val s = if (corruptAuth) "S=" + "0".repeat(40) else MsChapV2.authenticatorResponse(ntHash, nt, ch)
        cp(0xC223, 3, p.u8(1), "$s M=welcome".toByteArray())
        cp(0x8021, 1, 1, byteArrayOf(3, 6) + serverIp)
    }

    private fun ipcp(p: ByteArray) {
        if (p[0].toInt() != 1) return
        if (p.copyOfRange(6, 10).all { it.toInt() == 0 }) cp(0x8021, 3, p.u8(1), byteArrayOf(3, 6) + clientIp)
        else cp(0x8021, 2, p.u8(1), p.copyOfRange(4, p.size))
    }

    private fun tcp(flags: Int, port: Int, data: ByteArray = ByteArray(0), mss: Int? = null) {
        val seg = TcpSeg(22, port, srvSeq, cliNext, flags, 60000, mss, data).build(target, clientIp)
        ppp(0x0021, Ip4.build(target, clientIp, 6, seg, 1))
    }

    private fun ip(pkt: ByteArray) {
        val parsed = Ip4.parse(pkt)!!
        target = parsed.dst
        val seg = TcpSeg.parse(parsed.payload)!!
        val f = seg.flags
        when {
            f and TcpSeg.SYN != 0 -> {
                cliNext = seg.seq + 1
                tcp(TcpSeg.SYN or TcpSeg.ACK, seg.srcPort, mss = 1200)
                srvSeq++
            }
            seg.data.isNotEmpty() -> {
                received.append(String(seg.data))
                cliNext = seg.seq + seg.data.size
                tcp(TcpSeg.ACK, seg.srcPort)
            }
            f and TcpSeg.FIN != 0 -> {
                sawFin = true
                cliNext = seg.seq + 1
                tcp(TcpSeg.ACK or TcpSeg.FIN, seg.srcPort)
            }
            srvSeq == 1001 -> { // handshake ACK: greet like sshd
                val banner = "SSH-2.0-fake\r\n".toByteArray()
                tcp(TcpSeg.ACK or TcpSeg.PSH, seg.srcPort, banner)
                srvSeq += banner.size
            }
        }
    }
}

class TunnelTest {
    private fun connect(server: FakeServer, pw: String = "secret", log: MutableList<String>): SstpTunnel {
        server.start()
        val sock = Socket(InetAddress.getByName("127.0.0.1"), server.srv.localPort)
        val t = SstpTunnel("vpn.test", "USER", pw) { n, d -> log += "$n: $d" }
        t.attach(sock.getInputStream(), sock.getOutputStream(), server.cert)
        t.await()
        return t
    }

    @Test
    fun fullStackBanner() {
        val server = FakeServer("secret")
        val log = CopyOnWriteArrayList<String>()
        val t = connect(server, log = log)
        val flow = try { t.openTcp(InetAddress.getByName("10.9.9.9"), 22) } catch (e: Exception) { fail("${e.message} trace=${server.trace} log=$log") }
        val buf = ByteArray(64)
        val n = flow.read(buf, 0, buf.size, 5000)
        assertEquals("SSH-2.0-fake\r\n", String(buf, 0, n))
        flow.write("hello".toByteArray(), 0, 5)
        flow.close()
        t.close()
        assertTrue(server.bindingOk, "crypto binding rejected by fake server")
        assertTrue(server.sawFin)
        assertTrue(log.none { it.startsWith("error:") }, "tunnel reported an error: $log")
        assertEquals("hello", server.received.toString())
        for (stage in listOf("http", "sstp-ack", "lcp", "auth", "crypto-binding", "ipcp", "tcp")) {
            assertTrue(log.any { it.startsWith("$stage:") }, "missing stage $stage in $log")
        }
        assertTrue(log.none { it.contains("secret") })
    }

    @Test
    fun wrongPasswordFailsOnce() {
        try {
            connect(FakeServer("secret"), pw = "nope", log = CopyOnWriteArrayList())
            fail("should not authenticate")
        } catch (e: SstpException) {
            assertTrue(e.message!!.contains("E=691"), e.message)
        }
    }

    @Test
    fun rejectsServerWithBadAuthenticatorResponse() {
        try {
            connect(FakeServer("secret", corruptAuth = true), log = CopyOnWriteArrayList())
            fail("should reject")
        } catch (e: SstpException) {
            assertTrue(e.message!!.contains("authenticator"), e.message)
        }
    }
}
