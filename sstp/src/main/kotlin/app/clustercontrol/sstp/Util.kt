package app.clustercontrol.sstp

import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SstpException(message: String) : IOException(message)

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

internal fun hexToBytes(s: String): ByteArray {
    val h = s.filter { it.isLetterOrDigit() }
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.u8(o: Int) = this[o].toInt() and 0xFF
internal fun ByteArray.u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
internal fun ByteArray.u32(o: Int) = (u16(o) shl 16) or u16(o + 2)
internal fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
internal fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

internal fun digest(alg: String, vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance(alg).also { md -> parts.forEach { md.update(it) } }.digest()

internal fun sha1(vararg parts: ByteArray) = digest("SHA-1", *parts)

internal fun hmac(alg: String, key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance(alg).also { it.init(SecretKeySpec(key, alg)) }.doFinal(data)
