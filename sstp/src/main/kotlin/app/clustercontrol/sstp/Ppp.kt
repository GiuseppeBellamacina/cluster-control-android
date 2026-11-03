package app.clustercontrol.sstp

import java.security.SecureRandom

internal interface PppEvents {
    fun stage(name: String, detail: String)

    /** MS-CHAPv2 succeeded; [hlak] feeds the SSTP crypto binding. */
    fun authenticated(hlak: ByteArray)
    fun ready()
    fun failed(reason: String)
}

/**
 * Client side of PPP over SSTP: LCP, MS-CHAPv2 and IPCP. Frames are exchanged without HDLC flags/FCS;
 * the address/control bytes are always sent (PFC/ACFC are rejected during LCP).
 */
internal class Ppp(
    private val user: String,
    private val password: String,
    private val authenticate: Boolean,
    private val sendFrame: (ByteArray) -> Unit,
    private val ev: PppEvents,
) {
    private val rnd = SecureRandom()
    private var nextId = 0
    private var done = false

    var localIp = ByteArray(4); private set
    var peerIp = ByteArray(4); private set

    private val magic = ByteArray(4).also { rnd.nextBytes(it) }

    private val lcp: Ncp = Ncp(
        0xC021, "LCP",
        mapOf(1 to be16(1400), 5 to magic),
        judge = { t, v ->
            when (t) {
                1, 2, 5 -> ACK
                3 -> if (v.contentEquals(CHAP_MSV2)) ACK else NAK
                else -> REJ // PFC, ACFC, multilink, callback...
            }
        },
        nakValue = { _, _ -> CHAP_MSV2 },
        onOpen = { ev.stage("lcp", "up") },
    )

    private val ipcp: Ncp = Ncp(
        0x8021, "IPCP",
        mapOf(3 to ByteArray(4)),
        judge = { t, _ -> if (t == 3) ACK else REJ },
        nakValue = { _, v -> v },
        onOpen = {
            localIp = ipcp.opts[3]!!
            ev.stage("ipcp", "up, assigned ${ip(localIp)}, peer ${ip(peerIp)}")
            ev.ready()
        },
        onPeerOptions = { l -> l.firstOrNull { it.first == 3 }?.let { peerIp = it.second } },
    )

    private var authChallenge = ByteArray(0)
    private var peerChallenge = ByteArray(0)
    private var ntResp = ByteArray(0)
    private val ntHash by lazy { MsChapV2.ntHash(password) }

    @Synchronized
    fun start() = lcp.open()

    @Synchronized
    fun tick() {
        if (done) return
        val now = System.currentTimeMillis()
        lcp.tick(now)
        ipcp.tick(now)
    }

    fun sendIp(packet: ByteArray) = sendFrame(frame(0x0021, packet))

    /** Handles one PPP frame; returns the IPv4 packet if the frame carried one. */
    @Synchronized
    fun onFrame(f: ByteArray): ByteArray? {
        if (done || f.size < 3) return null
        var p = if (f[0] == 0xFF.toByte() && f[1] == 0x03.toByte()) 2 else 0
        val proto = if (f.u8(p) and 1 == 1) f.u8(p++) else f.u16(p).also { p += 2 }
        val payload = f.copyOfRange(p, f.size)
        when (proto) {
            0x0021 -> return payload
            0xC021 -> cp(lcp, payload) { lcpExtra(it) }
            0x8021 -> cp(ipcp, payload) {}
            0xC223 -> chap(payload)
            else -> {
                ev.stage("ppp", "protocol 0x%04X rejected".format(proto))
                sendCp(0xC021, 8, nextId(), be16(proto) + payload.copyOf(minOf(payload.size, 100)))
            }
        }
        return null
    }

    private fun cp(n: Ncp, payload: ByteArray, extra: (Triple<Int, Int, ByteArray>) -> Unit) {
        if (payload.size < 4) return
        val code = payload.u8(0)
        val id = payload.u8(1)
        val len = minOf(payload.u16(2), payload.size)
        val data = payload.copyOfRange(4, maxOf(4, len))
        if (code <= 4) n.onPacket(code, id, data) else extra(Triple(code, id, data))
    }

    private fun lcpExtra(c: Triple<Int, Int, ByteArray>) {
        val (code, id, data) = c
        when (code) {
            5 -> {
                sendCp(0xC021, 6, id, ByteArray(0))
                fail("server terminated the PPP link")
            }
            9 -> sendCp(0xC021, 10, id, magic + data.copyOfRange(minOf(4, data.size), data.size))
            7, 8 -> ev.stage("lcp", "peer sent ${if (code == 7) "Code" else "Protocol"}-Reject")
        }
    }

    private fun chap(p: ByteArray) {
        if (p.size < 4) return
        val code = p.u8(0)
        val id = p.u8(1)
        val data = p.copyOfRange(4, minOf(p.size, maxOf(4, p.u16(2))))
        when (code) {
            1 -> {
                authChallenge = data.copyOfRange(1, 1 + data.u8(0))
                if (!authenticate) {
                    ev.stage("auth", "CHAP challenge received (${authChallenge.size} bytes); stopping before sending credentials")
                    ev.ready()
                    return
                }
                peerChallenge = ByteArray(16).also { rnd.nextBytes(it) }
                ntResp = MsChapV2.ntResponse(MsChapV2.challengeHash(peerChallenge, authChallenge, user), ntHash)
                val value = peerChallenge + ByteArray(8) + ntResp + byteArrayOf(0)
                sendCp(0xC223, 2, id, byteArrayOf(value.size.toByte()) + value + user.toByteArray())
                ev.stage("auth", "MS-CHAPv2 response sent")
            }
            3 -> {
                val msg = String(data, Charsets.ISO_8859_1)
                val expected = MsChapV2.authenticatorResponse(
                    ntHash, ntResp, MsChapV2.challengeHash(peerChallenge, authChallenge, user),
                )
                if (!msg.startsWith(expected)) return fail("MS-CHAPv2 success but the server authenticator response does not match")
                ev.stage("auth", "MS-CHAPv2 success, server authenticated")
                ev.authenticated(MsChapV2.hlak(ntHash, ntResp))
                ipcp.open()
            }
            4 -> {
                val e = Regex("E=(\\d+)").find(String(data, Charsets.ISO_8859_1))?.groupValues?.get(1)
                fail("MS-CHAPv2 authentication failed (E=$e)")
            }
        }
    }

    private fun fail(reason: String) {
        if (done) return
        done = true
        ev.failed(reason)
    }

    private fun nextId(): Int {
        nextId = (nextId + 1) and 0xFF
        return nextId
    }

    private fun frame(proto: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), 3) + be16(proto) + payload

    private fun sendCp(proto: Int, code: Int, id: Int, data: ByteArray) =
        sendFrame(frame(proto, byteArrayOf(code.toByte(), id.toByte()) + be16(data.size + 4) + data))

    private fun ip(a: ByteArray) = a.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun encode(opts: Collection<Pair<Int, ByteArray>>) =
        opts.fold(ByteArray(0)) { acc, (t, v) -> acc + byteArrayOf(t.toByte(), (v.size + 2).toByte()) + v }

    private fun decode(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var p = 0
        while (p + 2 <= data.size) {
            val len = data.u8(p + 1)
            if (len < 2 || p + len > data.size) break
            out += data.u8(p) to data.copyOfRange(p + 2, p + len)
            p += len
        }
        return out
    }

    /** One LCP/IPCP automaton: our request is retried until acked, the peer's request is judged option by option. */
    private inner class Ncp(
        val proto: Int,
        val name: String,
        initial: Map<Int, ByteArray>,
        val judge: (Int, ByteArray) -> Int,
        val nakValue: (Int, ByteArray) -> ByteArray,
        val onOpen: () -> Unit,
        val onPeerOptions: (List<Pair<Int, ByteArray>>) -> Unit = {},
    ) {
        val opts = LinkedHashMap(initial)
        private var id = 0
        private var lastReq = 0L
        private var tries = 0
        private var started = false
        private var localOk = false
        private var remoteOk = false
        private var opened = false

        fun open() {
            started = true
            request()
        }

        private fun request() {
            id = nextId()
            localOk = false
            lastReq = System.currentTimeMillis()
            tries++
            sendCp(proto, 1, id, encode(opts.map { it.key to it.value }))
        }

        fun tick(now: Long) {
            if (!started || localOk || now - lastReq < 3000) return
            if (tries > 10) fail("$name negotiation timed out") else request()
        }

        fun onPacket(code: Int, rid: Int, data: ByteArray) {
            when (code) {
                1 -> peerRequest(rid, data)
                2 -> if (rid == id) {
                    localOk = true
                    check()
                }
                3 -> if (rid == id) {
                    decode(data).forEach { (t, v) -> opts[t] = v }
                    request()
                }
                4 -> if (rid == id) {
                    val rejected = decode(data).map { it.first }
                    ev.stage(name.lowercase(), "options rejected by peer: $rejected")
                    rejected.forEach { opts.remove(it) }
                    request()
                }
            }
        }

        private fun peerRequest(rid: Int, data: ByteArray) {
            val list = decode(data)
            val rej = list.filter { judge(it.first, it.second) == REJ }
            val nak = list.filter { judge(it.first, it.second) == NAK }
            when {
                rej.isNotEmpty() -> {
                    sendCp(proto, 4, rid, encode(rej))
                    remoteOk = false
                }
                nak.isNotEmpty() -> {
                    sendCp(proto, 3, rid, encode(nak.map { it.first to nakValue(it.first, it.second) }))
                    remoteOk = false
                }
                else -> {
                    sendCp(proto, 2, rid, data)
                    remoteOk = true
                    onPeerOptions(list)
                    check()
                }
            }
        }

        private fun check() {
            if (localOk && remoteOk && !opened) {
                opened = true
                onOpen()
            }
        }
    }

    private companion object {
        const val ACK = 0
        const val NAK = 1
        const val REJ = 2
        val CHAP_MSV2 = byteArrayOf(0xC2.toByte(), 0x23, 0x81.toByte())
    }
}
