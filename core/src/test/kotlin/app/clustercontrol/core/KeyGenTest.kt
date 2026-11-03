package app.clustercontrol.core

import com.jcraft.jsch.JSch
import kotlin.test.Test
import kotlin.test.assertTrue

class KeyGenTest {
    @Test
    fun generatedKeyLoadsInJsch() {
        val key = KeyGen.generate("cc-test")
        assertTrue(key.publicKeyLine.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(key.publicKeyLine.endsWith(" cc-test"))
        assertTrue(key.privateKeyPem.startsWith("-----BEGIN EC PRIVATE KEY-----"))
        JSch().addIdentity("k", key.privateKeyPem.toByteArray(), null, null)
    }
}
