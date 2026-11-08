package app.clustercontrol

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The credentials. A build made with secrets.properties carries them and [init] stores them encrypted; a seedless
 * build (the public GitHub release) carries nothing and keeps what an earlier install stored, so updating from a
 * release never loses them.
 */
object Seed {
    const val REPO = "GiuseppeBellamacina/neuro_symbolic_t2g"
    const val BRANCH = "main"
    const val APP_REPO = "GiuseppeBellamacina/cluster-control-android"
    const val SSH_PORT = 22

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        val p = EncryptedSharedPreferences.create(
            ctx,
            "credentials",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs = p
        if (BuildConfig.SEED_SSH_KEY.isNotEmpty()) {
            p.edit()
                .putString("vpnHost", BuildConfig.SEED_VPN_HOST).putString("vpnUser", BuildConfig.SEED_VPN_USER)
                .putString("vpnPassword", BuildConfig.SEED_VPN_PASSWORD).putString("sshHost", BuildConfig.SEED_SSH_HOST)
                .putString("sshUser", BuildConfig.SEED_SSH_USER).putString("sshKey", BuildConfig.SEED_SSH_KEY)
                .apply()
        }
    }

    // the build-time value is only the fallback when nothing is stored (JVM tests, first run)
    private fun get(key: String, built: String) = prefs?.getString(key, null) ?: built

    val vpnHost get() = get("vpnHost", BuildConfig.SEED_VPN_HOST)
    val vpnUser get() = get("vpnUser", BuildConfig.SEED_VPN_USER)
    val vpnPassword get() = get("vpnPassword", BuildConfig.SEED_VPN_PASSWORD)
    val sshHost get() = get("sshHost", BuildConfig.SEED_SSH_HOST)
    val sshUser get() = get("sshUser", BuildConfig.SEED_SSH_USER)
    val sshKey get() = get("sshKey", BuildConfig.SEED_SSH_KEY)

    val host get() = sshHost.ifEmpty { "gcluster.dmi.unict.it" }
    val vpn get() = vpnHost.ifEmpty { "vpnstudenti.unict.it" }

    val missing: List<String>
        get() = listOfNotNull(
            "utente VPN".takeIf { vpnUser.isEmpty() },
            "password VPN".takeIf { vpnPassword.isEmpty() },
            "utente SSH".takeIf { sshUser.isEmpty() },
            "chiave SSH".takeIf { sshKey.isEmpty() },
        )

    val embedded get() = missing.isEmpty()
}
