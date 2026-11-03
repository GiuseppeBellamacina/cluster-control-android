package app.clustercontrol.core

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/** An unencrypted PEM private key and its `authorized_keys` line. */
data class GeneratedKey(val privateKeyPem: String, val publicKeyLine: String)

object KeyGen {
    /** ECDSA P-256: JSch cannot export Ed25519 private keys as PEM (UnsupportedOperationException). */
    fun generate(comment: String): GeneratedKey {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        try {
            val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }
            return GeneratedKey(priv.toString(Charsets.UTF_8), pub.toString(Charsets.UTF_8).trim())
        } finally {
            kp.dispose()
        }
    }
}
