package app.clustercontrol

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.clustercontrol.core.HostKeys

/** The trusted ssh host key (trust on first use), in EncryptedSharedPreferences (master key in the Android Keystore). */
class SecureHostKeys(ctx: Context) : HostKeys {
    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "secure_settings",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(host: String) = prefs.getString("hostkey.$host", null)

    override fun put(host: String, fingerprint: String) {
        prefs.edit().putString("hostkey.$host", fingerprint).apply()
    }
}
