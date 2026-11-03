package app.clustercontrol

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.clustercontrol.core.HostKeys

/** All settings live in EncryptedSharedPreferences (AES256 keys + values, master key in the Android Keystore). */
class SecureSettings(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "secure_settings",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized
    fun load(): AppSettings {
        val d = AppSettings()
        fun s(k: String, def: String) = prefs.getString(k, def) ?: def
        return AppSettings(
            host = s("host", d.host), user = s("user", d.user), port = prefs.getInt("port", d.port),
            privateKey = s("privateKey", ""), publicKey = s("publicKey", ""), passphrase = s("passphrase", ""),
            repo = s("repo", d.repo), branch = s("branch", d.branch), githubToken = s("githubToken", ""),
            transport = runCatching { TransportKind.valueOf(s("transport", d.transport.name)) }.getOrDefault(d.transport),
            vpnHost = s("vpnHost", d.vpnHost), vpnUser = s("vpnUser", ""), vpnPassword = s("vpnPassword", ""),
            includeSrc = prefs.getBoolean("includeSrc", false),
        )
    }

    @Synchronized
    fun save(s: AppSettings) {
        prefs.edit()
            .putString("host", s.host).putString("user", s.user).putInt("port", s.port)
            .putString("privateKey", s.privateKey).putString("publicKey", s.publicKey).putString("passphrase", s.passphrase)
            .putString("repo", s.repo).putString("branch", s.branch).putString("githubToken", s.githubToken)
            .putString("transport", s.transport.name)
            .putString("vpnHost", s.vpnHost).putString("vpnUser", s.vpnUser).putString("vpnPassword", s.vpnPassword)
            .putBoolean("includeSrc", s.includeSrc)
            .apply()
    }

    val hostKeys = object : HostKeys {
        override fun get(host: String) = prefs.getString("hostkey.$host", null)
        override fun put(host: String, fingerprint: String) {
            prefs.edit().putString("hostkey.$host", fingerprint).apply()
        }
    }

    fun forgetHostKey(host: String) = prefs.edit().remove("hostkey.$host").apply()
}
