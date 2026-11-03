package app.clustercontrol.sstp

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** MS-CHAPv2 (RFC 2759) and the MPPE key derivation of RFC 3079 that SSTP crypto binding reuses. */
object MsChapV2 {
    private val MAGIC1 = "Magic server to client signing constant".toByteArray()
    private val MAGIC2 = "Pad to make it do more than one iteration".toByteArray()
    private val MPPE_MASTER = "This is the MPPE Master Key".toByteArray()
    private val MPPE_SEND = "On the client side, this is the send key; on the server side, it is the receive key.".toByteArray()
    private val MPPE_RECV = "On the client side, this is the receive key; on the server side, it is the send key.".toByteArray()

    fun ntHash(password: String) = Md4.digest(password.toByteArray(Charsets.UTF_16LE))

    fun challengeHash(peerChallenge: ByteArray, authChallenge: ByteArray, user: String) =
        sha1(peerChallenge, authChallenge, user.substringAfterLast(92.toChar()).toByteArray()).copyOf(8)

    fun ntResponse(challenge: ByteArray, ntHash: ByteArray): ByteArray {
        val key21 = ntHash.copyOf(21)
        return (0 until 3).map { desEncrypt(key21.copyOfRange(it * 7, it * 7 + 7), challenge) }
            .reduce { a, b -> a + b }
    }

    /** The "S=..." string the server must return to prove it knows the password too. */
    fun authenticatorResponse(ntHash: ByteArray, ntResponse: ByteArray, challenge: ByteArray): String {
        val d1 = sha1(Md4.digest(ntHash), ntResponse, MAGIC1)
        return "S=" + sha1(d1, challenge, MAGIC2).hex().uppercase()
    }

    fun masterKey(ntHash: ByteArray, ntResponse: ByteArray) =
        sha1(Md4.digest(ntHash), ntResponse, MPPE_MASTER).copyOf(16)

    fun asymmetricStartKey(master: ByteArray, isSend: Boolean, isServer: Boolean): ByteArray {
        val s = if (isSend == isServer) MPPE_RECV else MPPE_SEND
        return sha1(master, ByteArray(40), s, ByteArray(40) { 0xF2.toByte() }).copyOf(16)
    }

    /** HLAK for SSTP crypto binding: client send key followed by client receive key. */
    fun hlak(ntHash: ByteArray, ntResponse: ByteArray): ByteArray {
        val m = masterKey(ntHash, ntResponse)
        return asymmetricStartKey(m, isSend = true, isServer = false) +
            asymmetricStartKey(m, isSend = false, isServer = false)
    }

    private fun desEncrypt(key7: ByteArray, data: ByteArray): ByteArray {
        val k = ByteArray(8)
        var bits = 0L
        for (b in key7) bits = (bits shl 8) or (b.toLong() and 0xFF)
        for (i in 0 until 8) k[i] = (((bits ushr (49 - 7 * i)) and 0x7F).toInt() shl 1).toByte()
        return Cipher.getInstance("DES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "DES"))
            doFinal(data)
        }
    }
}
