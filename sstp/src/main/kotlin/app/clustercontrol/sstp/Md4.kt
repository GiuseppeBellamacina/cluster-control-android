package app.clustercontrol.sstp

/** MD4 (RFC 1320); modern JDKs no longer ship it but the NT password hash needs it. */
object Md4 {
    private val S1 = intArrayOf(3, 7, 11, 19)
    private val S2 = intArrayOf(3, 5, 9, 13)
    private val S3 = intArrayOf(3, 9, 11, 15)
    private val K2 = intArrayOf(0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15)
    private val K3 = intArrayOf(0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15)

    fun digest(msg: ByteArray): ByteArray {
        val padded = ByteArray(((msg.size + 8) / 64 + 1) * 64)
        msg.copyInto(padded)
        padded[msg.size] = 0x80.toByte()
        val bits = msg.size.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bits ushr (8 * i)).toByte()

        val h = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
        val x = IntArray(16)
        for (blk in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = blk + i * 4
                x[i] = (padded[o].toInt() and 0xFF) or ((padded[o + 1].toInt() and 0xFF) shl 8) or
                    ((padded[o + 2].toInt() and 0xFF) shl 16) or ((padded[o + 3].toInt() and 0xFF) shl 24)
            }
            val v = h.copyOf()
            for (i in 0 until 48) {
                // the register being updated rotates a, d, c, b, a, ...
                val a = (4 - i % 4) % 4
                val b = (a + 1) % 4
                val c = (a + 2) % 4
                val d = (a + 3) % 4
                val (f, k, s) = when {
                    i < 16 -> Triple((v[b] and v[c]) or (v[b].inv() and v[d]) , x[i], S1[i % 4])
                    i < 32 -> Triple(((v[b] and v[c]) or (v[b] and v[d]) or (v[c] and v[d])) + 0x5A827999, x[K2[i - 16]], S2[i % 4])
                    else -> Triple((v[b] xor v[c] xor v[d]) + 0x6ED9EBA1, x[K3[i - 32]], S3[i % 4])
                }
                v[a] = Integer.rotateLeft(v[a] + f + k, s)
            }
            for (i in 0 until 4) h[i] += v[i]
        }
        return ByteArray(16) { (h[it / 4] ushr (8 * (it % 4))).toByte() }
    }
}
