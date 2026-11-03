package app.clustercontrol.sstp

/** MS-SSTP wire format: 4-byte header (version 0x10, C bit, 12-bit length) then a PPP frame or a control message. */
internal object Sstp {
    const val CONNECT_REQUEST = 1
    const val CONNECT_ACK = 2
    const val CONNECT_NAK = 3
    const val CONNECTED = 4
    const val ABORT = 5
    const val DISCONNECT = 6
    const val DISCONNECT_ACK = 7
    const val ECHO_REQUEST = 8
    const val ECHO_RESPONSE = 9

    const val ATTR_ENCAPSULATED_PROTOCOL = 1
    const val ATTR_STATUS_INFO = 2
    const val ATTR_CRYPTO_BINDING = 3
    const val ATTR_CRYPTO_BINDING_REQ = 4

    const val HASH_SHA1 = 1
    const val HASH_SHA256 = 2

    class Control(val type: Int, val attrs: Map<Int, ByteArray>)

    fun packet(control: Boolean, body: ByteArray): ByteArray {
        val len = body.size + 4
        require(len < 4096) { "SSTP packet too long" }
        return byteArrayOf(0x10, if (control) 1 else 0, (len shr 8).toByte(), len.toByte()) + body
    }

    fun control(type: Int, attrs: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        var body = be16(type) + be16(attrs.size)
        for ((id, v) in attrs) body += byteArrayOf(0, id.toByte()) + be16(v.size + 4) + v
        return packet(true, body)
    }

    fun connectRequest() = control(CONNECT_REQUEST, listOf(ATTR_ENCAPSULATED_PROTOCOL to be16(1)))

    /** [body] is the packet without its 4-byte header. */
    fun parseControl(body: ByteArray): Control {
        if (body.size < 4) throw SstpException("short SSTP control message")
        val n = body.u16(2)
        val attrs = LinkedHashMap<Int, ByteArray>()
        var p = 4
        repeat(n) {
            if (p + 4 > body.size) throw SstpException("truncated SSTP attribute")
            val len = body.u16(p + 2)
            if (len < 4 || p + len > body.size) throw SstpException("bad SSTP attribute length")
            attrs[body.u8(p + 1)] = body.copyOfRange(p + 4, p + len)
            p += len
        }
        return Control(body.u16(0), attrs)
    }

    fun hashAlg(proto: Int) = if (proto == HASH_SHA256) "SHA-256" else "SHA-1"
    private fun hmacAlg(proto: Int) = if (proto == HASH_SHA256) "HmacSHA256" else "HmacSHA1"

    /** Compound MAC key: HMAC(HLAK, label | output length (LE16) | 0x01). */
    fun cmk(hlak: ByteArray, hashProto: Int): ByteArray {
        val len = if (hashProto == HASH_SHA256) 32 else 20
        val seed = "SSTP inner method derived CMK".toByteArray() + byteArrayOf(len.toByte(), 0, 1)
        return hmac(hmacAlg(hashProto), hlak, seed)
    }

    /** Call Connected carrying the Crypto Binding attribute; the MAC covers the whole packet with the MAC field zeroed. */
    fun callConnected(hashProto: Int, nonce: ByteArray, serverCertDer: ByteArray, hlak: ByteArray): ByteArray {
        val certHash = digest(hashAlg(hashProto), serverCertDer).copyOf(32)
        val value = byteArrayOf(0, 0, 0, hashProto.toByte()) + nonce + certHash + ByteArray(32)
        val pkt = control(CONNECTED, listOf(ATTR_CRYPTO_BINDING to value))
        val mac = hmac(hmacAlg(hashProto), cmk(hlak, hashProto), pkt)
        mac.copyInto(pkt, pkt.size - 32)
        return pkt
    }
}
