package app.clustercontrol.sstp

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object Ip4 {
    fun checksum(d: ByteArray, off: Int = 0, len: Int = d.size, init: Int = 0): Int {
        var sum = init
        var i = off
        while (i + 1 < off + len) { sum += d.u16(i); i += 2 }
        if (len % 2 == 1) sum += d.u8(off + len - 1) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    fun build(src: ByteArray, dst: ByteArray, proto: Int, payload: ByteArray, id: Int): ByteArray {
        val h = byteArrayOf(0x45, 0) + be16(20 + payload.size) + be16(id) + be16(0x4000) +
            byteArrayOf(64, proto.toByte(), 0, 0) + src + dst
        val c = checksum(h)
        h[10] = (c shr 8).toByte()
        h[11] = c.toByte()
        return h + payload
    }

    class Parsed(val src: ByteArray, val dst: ByteArray, val proto: Int, val payload: ByteArray)

    /** Returns null for anything that is not an unfragmented IPv4 packet. */
    fun parse(p: ByteArray): Parsed? {
        if (p.size < 20 || p.u8(0) shr 4 != 4) return null
        val ihl = (p.u8(0) and 15) * 4
        val total = p.u16(2)
        if (ihl < 20 || total > p.size || total < ihl || p.u16(6) and 0x3FFF != 0) return null
        return Parsed(p.copyOfRange(12, 16), p.copyOfRange(16, 20), p.u8(9), p.copyOfRange(ihl, total))
    }
}

internal class TcpSeg(
    val srcPort: Int, val dstPort: Int, val seq: Int, val ack: Int, val flags: Int,
    val window: Int, val mss: Int? = null, val data: ByteArray = ByteArray(0),
) {
    fun build(src: ByteArray, dst: ByteArray): ByteArray {
        val opts = if (mss != null) byteArrayOf(2, 4) + be16(mss) else ByteArray(0)
        val h = be16(srcPort) + be16(dstPort) + be32(seq) + be32(ack) +
            byteArrayOf(((20 + opts.size) / 4 shl 4).toByte(), flags.toByte()) + be16(window) + ByteArray(4) + opts
        val seg = h + data
        val pseudo = src + dst + byteArrayOf(0, 6) + be16(seg.size)
        val c = Ip4.checksum(seg, init = pseudo.let { p -> (0 until 12 step 2).sumOf { p.u16(it) } })
        seg[16] = (c shr 8).toByte()
        seg[17] = c.toByte()
        return seg
    }

    companion object {
        const val FIN = 1
        const val SYN = 2
        const val RST = 4
        const val PSH = 8
        const val ACK = 16

        fun parse(s: ByteArray): TcpSeg? {
            if (s.size < 20) return null
            val off = (s.u8(12) shr 4) * 4
            if (off < 20 || off > s.size) return null
            var mss: Int? = null
            var p = 20
            while (p + 1 < off) {
                val kind = s.u8(p)
                if (kind == 0) break
                if (kind == 1) { p++; continue }
                val len = s.u8(p + 1)
                if (len < 2) break
                if (kind == 2 && len == 4 && p + 4 <= off) mss = s.u16(p + 2)
                p += len
            }
            return TcpSeg(s.u16(0), s.u16(2), s.u32(4), s.u32(8), s.u8(13), s.u16(14), mss, s.copyOfRange(off, s.size))
        }
    }
}

/** Growable FIFO of bytes. */
internal class ByteQueue {
    private var a = ByteArray(4096)
    private var s = 0
    private var e = 0
    val size get() = e - s

    fun add(d: ByteArray, off: Int = 0, len: Int = d.size) {
        if (e + len > a.size) {
            val n = ByteArray(maxOf(a.size * 2, size + len))
            a.copyInto(n, 0, s, e)
            e -= s; s = 0; a = n
        }
        d.copyInto(a, e, off, off + len)
        e += len
    }

    fun peek(off: Int, n: Int) = a.copyOfRange(s + off, s + off + n)
    fun drop(n: Int) { s += n; if (s == e) { s = 0; e = 0 } }
}

/**
 * One TCP connection (client side) over raw IPv4 packets. All state is guarded by [lock]; segments arrive in order
 * on the SSTP/TLS stream, so out-of-order data is simply dropped and re-acked.
 * shortcut: no congestion control, no zero-window probe, fixed in-flight cap; fine for one SSH session.
 */
internal class TcpFlow(
    private val localIp: ByteArray,
    private val remoteIp: ByteArray,
    private val remotePort: Int,
    private val sendIp: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private enum class State { SYN_SENT, ESTABLISHED, CLOSED }

    private val rnd = SecureRandom()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val localPort = 49152 + rnd.nextInt(16000)
    private var ipId = rnd.nextInt(65536)

    private var state = State.SYN_SENT
    private var error: String? = null
    private val iss = rnd.nextInt()
    private var sndUna = iss
    private var sndNxt = iss
    private var rcvNxt = 0
    private var peerWnd = 0
    private var mss = MSS
    private val tx = ByteQueue()
    private val rx = ByteQueue()
    private var peerFin = false
    private var finPending = false
    private var finSent = false
    private var finAcked = false
    private var lastTx = 0L
    private var rto = 1000L
    private var retries = 0

    private fun lt(a: Int, b: Int) = a - b < 0
    private fun now() = System.currentTimeMillis()
    private fun rcvWnd() = (65535 - rx.size).coerceAtLeast(0)

    private fun send(flags: Int, seq: Int, data: ByteArray = ByteArray(0), mssOpt: Int? = null) {
        val seg = TcpSeg(localPort, remotePort, seq, rcvNxt, flags, rcvWnd(), mssOpt, data).build(localIp, remoteIp)
        ipId = (ipId + 1) and 0xFFFF
        sendIp(Ip4.build(localIp, remoteIp, 6, seg, ipId))
        lastTx = now()
    }

    private fun sendAck() = send(TcpSeg.ACK, sndNxt)

    fun connect(timeoutMs: Long): Unit = lock.withLock {
        sndNxt = iss + 1
        send(TcpSeg.SYN, iss, mssOpt = MSS)
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (state == State.SYN_SENT && left > 0) left = changed.awaitNanos(left)
        if (state != State.ESTABLISHED) {
            abort()
            throw IOException(error ?: "TCP connect timed out")
        }
    }

    fun onIp(pkt: ByteArray) {
        val ip = Ip4.parse(pkt) ?: return
        if (ip.proto != 6 || !ip.dst.contentEquals(localIp) || !ip.src.contentEquals(remoteIp)) return
        val seg = TcpSeg.parse(ip.payload) ?: return
        if (seg.dstPort != localPort || seg.srcPort != remotePort) return
        lock.withLock {
            if (state == State.CLOSED) return
            if (seg.flags and TcpSeg.RST != 0) return fail(if (state == State.SYN_SENT) "connection refused" else "connection reset by peer")
            if (state == State.SYN_SENT) {
                if (seg.flags and TcpSeg.SYN != 0 && seg.flags and TcpSeg.ACK != 0 && seg.ack == iss + 1) {
                    rcvNxt = seg.seq + 1
                    sndUna = seg.ack
                    peerWnd = seg.window
                    mss = minOf(MSS, seg.mss ?: 536)
                    log("SYN-ACK received, peer mss ${seg.mss}, window ${seg.window}")
                    state = State.ESTABLISHED
                    retries = 0
                    sendAck()
                    changed.signalAll()
                }
                return
            }
            if (seg.flags and TcpSeg.ACK != 0) onAck(seg.ack, seg.window)
            onData(seg)
        }
    }

    private fun onAck(ack: Int, wnd: Int) {
        if (lt(sndUna, ack) && !lt(sndNxt, ack)) {
            var acked = ack - sndUna
            if (finSent && ack == sndNxt) { finAcked = true; acked-- }
            tx.drop(minOf(acked, tx.size))
            sndUna = ack
            retries = 0
            rto = 1000
            lastTx = now()
        }
        peerWnd = wnd
        pump()
        changed.signalAll()
    }

    private fun onData(seg: TcpSeg) {
        val fin = seg.flags and TcpSeg.FIN != 0
        if (seg.data.isEmpty() && !fin) return
        var seq = seg.seq
        var data = seg.data
        if (lt(seq, rcvNxt)) {
            val trim = rcvNxt - seq
            if (trim > data.size || (trim == data.size && !fin)) return sendAck()
            data = data.copyOfRange(trim, data.size)
            seq = rcvNxt
        }
        if (seq != rcvNxt) return sendAck()
        if (data.isNotEmpty()) { rx.add(data); rcvNxt += data.size }
        if (fin) { rcvNxt++; peerFin = true }
        sendAck()
        changed.signalAll()
    }

    private fun pump() {
        if (state != State.ESTABLISHED || finSent) return
        while (true) {
            val inflight = sndNxt - sndUna - if (finSent) 1 else 0
            val n = minOf(tx.size - inflight, minOf(peerWnd, MAX_INFLIGHT) - inflight, mss)
            if (n <= 0) break
            send(TcpSeg.ACK or TcpSeg.PSH, sndNxt, tx.peek(inflight, n))
            sndNxt += n
        }
        if (finPending && !finSent && tx.size == sndNxt - sndUna) {
            send(TcpSeg.ACK or TcpSeg.FIN, sndNxt)
            sndNxt++
            finSent = true
        }
    }

    /** Called periodically; retransmits the oldest unacknowledged segment. */
    fun tick(): Unit = lock.withLock {
        if (state == State.CLOSED || now() - lastTx < rto) return
        if (state == State.SYN_SENT) {
            send(TcpSeg.SYN, iss, mssOpt = MSS)
        } else if (sndUna != sndNxt) {
            val inflight = sndNxt - sndUna - if (finSent) 1 else 0
            if (inflight > 0) send(TcpSeg.ACK or TcpSeg.PSH, sndUna, tx.peek(0, minOf(mss, inflight)))
            else send(TcpSeg.ACK or TcpSeg.FIN, sndNxt - 1)
        } else return
        if (++retries > 8) return fail("TCP retransmission timeout")
        log("retransmit #$retries (rto ${rto}ms)")
        rto = minOf(rto * 2, 8000)
    }

    fun write(b: ByteArray, off: Int, len: Int): Unit = lock.withLock {
        var o = off
        var left = len
        while (left > 0) {
            while (state == State.ESTABLISHED && !finPending && tx.size >= TX_LIMIT) changed.await()
            if (state != State.ESTABLISHED || finPending) throw IOException(error ?: "socket closed")
            val n = minOf(left, TX_LIMIT - tx.size)
            tx.add(b, o, n)
            o += n
            left -= n
            pump()
        }
    }

    /** Returns -1 at end of stream; throws [SocketTimeoutException] after [timeoutMs] (0 = wait forever). */
    fun read(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        while (rx.size == 0) {
            if (state == State.CLOSED || peerFin) { error?.let { throw IOException(it) }; return -1 }
            if (timeoutMs == 0) changed.await()
            else if (left <= 0) throw SocketTimeoutException("read timed out")
            else left = changed.awaitNanos(left)
        }
        val wasSmall = rcvWnd() < 2 * mss
        val n = minOf(len, rx.size)
        rx.peek(0, n).copyInto(b, off)
        rx.drop(n)
        if (wasSmall && rcvWnd() >= 2 * mss) sendAck()
        n
    }

    fun shutdownOutput(): Unit = lock.withLock {
        finPending = true
        pump()
    }

    /** Sends FIN and waits briefly for its acknowledgement, then drops the connection. */
    fun close(): Unit = lock.withLock {
        if (state == State.ESTABLISHED) {
            shutdownOutput()
            var left = TimeUnit.SECONDS.toNanos(2)
            while (state == State.ESTABLISHED && !finAcked && left > 0) left = changed.awaitNanos(left)
        }
        state = State.CLOSED
        changed.signalAll()
    }

    private fun abort() {
        if (state == State.ESTABLISHED) runCatching { send(TcpSeg.RST or TcpSeg.ACK, sndNxt) }
        state = State.CLOSED
        changed.signalAll()
    }

    fun fail(reason: String): Unit = lock.withLock {
        error = reason
        state = State.CLOSED
        changed.signalAll()
    }

    val isClosed get() = lock.withLock { state == State.CLOSED }

    private companion object {
        const val MSS = 1350
        const val MAX_INFLIGHT = 32 * 1024
        const val TX_LIMIT = 64 * 1024
    }
}
