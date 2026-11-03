package app.clustercontrol.sstp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun md4Rfc1320() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", Md4.digest(ByteArray(0)).hex())
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", Md4.digest("a".toByteArray()).hex())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", Md4.digest("abc".toByteArray()).hex())
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            Md4.digest("12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray()).hex(),
        )
    }

    // RFC 2759 section 9.2
    private val authChallenge = hexToBytes("5B5D7C7D7B3F2F3E3C2C602132262628")
    private val peerChallenge = hexToBytes("21402324255E262A28295F2B3A337C7E")

    @Test
    fun msChapV2Rfc2759() {
        val challenge = MsChapV2.challengeHash(peerChallenge, authChallenge, "User")
        assertEquals("d02e4386bce91226", challenge.hex())
        val nt = MsChapV2.ntHash("clientPass")
        assertEquals("44ebba8d5312b8d611474411f56989ae", nt.hex())
        val resp = MsChapV2.ntResponse(challenge, nt)
        assertEquals("82309ecd8d708b5ea08faa3981cd83544233114a3d85d6df", resp.hex())
        assertEquals("S=407A5589115FD0D6209F510FE9C04566932CDA56", MsChapV2.authenticatorResponse(nt, resp, challenge))
    }

    @Test
    fun mppeKeysRfc3079() {
        val nt = MsChapV2.ntHash("clientPass")
        val resp = hexToBytes("82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF")
        val master = MsChapV2.masterKey(nt, resp)
        assertEquals("fdece3717a8c838cb388e527ae3cdd31", master.hex())
        val keys = listOf(true, false).map { MsChapV2.asymmetricStartKey(master, it, isServer = true).hex() }
        assertTrue("8b7cdc149b993a1ba118cb153f56dccb" in keys, "server keys: $keys")
    }

    @Test
    fun ipv4HeaderChecksum() {
        val h = hexToBytes("45000073000040004011 0000 c0a80001c0a800c7".replace(" ", ""))
        assertEquals(0xb861, Ip4.checksum(h))
    }

    @Test
    fun tcpRoundTrip() {
        val a = byteArrayOf(10, 0, 0, 2)
        val b = byteArrayOf(10, 0, 0, 1)
        val raw = TcpSeg(1234, 22, -5, 77, TcpSeg.SYN or TcpSeg.ACK, 1000, 1350, "hi".toByteArray()).build(a, b)
        val s = TcpSeg.parse(raw)!!
        assertEquals(1350, s.mss)
        assertEquals(-5, s.seq)
        assertContentEquals("hi".toByteArray(), s.data)
        val pkt = Ip4.parse(Ip4.build(a, b, 6, raw, 1))!!
        assertContentEquals(raw, pkt.payload)
    }

    @Test
    fun callConnectedLayout() {
        val nonce = ByteArray(32) { it.toByte() }
        val cert = "cert".toByteArray()
        val hlak = ByteArray(32) { 7 }
        for (proto in listOf(Sstp.HASH_SHA1, Sstp.HASH_SHA256)) {
            val p = Sstp.callConnected(proto, nonce, cert, hlak)
            assertEquals(112, p.size)
            assertEquals(112, ((p.u8(2) and 15) shl 8) or p.u8(3))
            val zeroed = p.copyOf().also { ByteArray(32).copyInto(it, 80) }
            val alg = if (proto == Sstp.HASH_SHA256) "HmacSHA256" else "HmacSHA1"
            val mac = hmac(alg, Sstp.cmk(hlak, proto), zeroed).copyOf(32)
            assertContentEquals(mac, p.copyOfRange(80, 112))
            assertContentEquals(digest(Sstp.hashAlg(proto), cert).copyOf(32), p.copyOfRange(48, 80))
        }
    }
}
