package app.clustercontrol

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** What the owner types in Altro > Configurazione. A blank text field keeps the stored value. */
data class ConfigInput(
    val useVpn: Boolean,
    val vpnHost: String,
    val vpnUser: String,
    val vpnPassword: String,
    val host: String,
    val port: String,
    val projectDir: String,
    val sshUser: String,
    val sshKey: String,
    val repo: String,
    val branch: String,
)

/** The non-secret part, to prefill the form; the secrets are only reported as present. */
data class ConfigView(
    val useVpn: Boolean = true,
    val vpnHost: String = "",
    val vpnUser: String = "",
    val host: String = "",
    val port: String = "",
    val projectDir: String = "",
    val sshUser: String = "",
    val repo: String = "",
    val branch: String = "",
    val hasVpnPassword: Boolean = false,
    val hasKey: Boolean = false,
)

/** The project folder on the cluster, relative to the home directory: `~/proj/` -> `proj`. */
fun normalizeProjectDir(raw: String) = raw.trim().removePrefix("~/").trim('/')

/** Why [i] cannot be saved (Italian, for the owner), or null. */
fun configError(i: ConfigInput): String? = when {
    i.sshKey.isNotBlank() && "PRIVATE KEY" !in i.sshKey -> "La chiave non sembra una chiave privata"
    i.port.isNotBlank() && i.port.trim().toIntOrNull() !in 1..65535 -> "Porta non valida"
    i.projectDir.trim().removePrefix("~/").startsWith("/") -> "La cartella è relativa alla home del cluster"
    i.repo.isNotBlank() && !Regex("[\\w.-]+/[\\w.-]+").matches(i.repo.trim()) -> "Repo nel formato proprietario/nome"
    else -> null
}

/**
 * Everything the app needs to reach a cluster and its repo, all of it typed by the owner and stored encrypted on the
 * phone (host names and user names included). Nothing about a specific cluster or account is built into the app.
 */
object Creds {
    /** Where the in-app updates come from (the app's own release channel). */
    const val APP_REPO = "GiuseppeBellamacina/cluster-control-android"

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = EncryptedSharedPreferences.create(
            ctx,
            "credentials",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun get(key: String) = prefs?.getString(key, null).orEmpty()

    val host get() = get("sshHost")
    val sshPort get() = get("sshPort").toIntOrNull() ?: 22
    val projectDir get() = normalizeProjectDir(get("projectDir"))
    val sshUser get() = get("sshUser")
    val sshKey get() = get("sshKey")
    val vpnHost get() = get("vpnHost")
    val vpnUser get() = get("vpnUser")
    val vpnPassword get() = get("vpnPassword")
    val repo get() = get("repo")
    val branch get() = get("branch").ifEmpty { "main" }

    /** Without a stored choice (an older install) the VPN is used when a VPN host was stored. */
    val useVpn: Boolean get() = prefs?.getString("useVpn", null)?.let { it == "1" } ?: vpnHost.isNotEmpty()

    /** The project folder as the shell on the cluster sees it. */
    val remoteRoot get() = "~/$projectDir"

    fun save(i: ConfigInput) {
        val e = prefs?.edit() ?: return
        e.putString("useVpn", if (i.useVpn) "1" else "0")
        fun put(key: String, value: String) {
            value.trim().takeIf { it.isNotEmpty() }?.let { e.putString(key, it) }
        }
        put("vpnHost", i.vpnHost)
        put("vpnUser", i.vpnUser)
        put("vpnPassword", i.vpnPassword)
        put("sshHost", i.host)
        put("sshPort", i.port)
        put("projectDir", normalizeProjectDir(i.projectDir))
        put("sshUser", i.sshUser)
        // JSch wants a clean PEM: LF line ends, one final newline
        i.sshKey.replace("\r\n", "\n").trim().takeIf { it.isNotEmpty() }?.let { e.putString("sshKey", it + "\n") }
        put("repo", i.repo)
        put("branch", i.branch)
        e.apply()
    }

    fun view() = ConfigView(
        useVpn, vpnHost, vpnUser, host, if (get("sshPort").isEmpty()) "" else sshPort.toString(), projectDir, sshUser,
        repo, get("branch"), vpnPassword.isNotEmpty(), sshKey.isNotEmpty(),
    )

    /** What the cluster connection needs (the GitHub repo is only for the queue catalog and the sync). */
    val connectionMissing: List<String>
        get() = buildList {
            if (host.isEmpty()) add("host cluster")
            if (projectDir.isEmpty()) add("cartella progetto")
            if (sshUser.isEmpty()) add("utente SSH")
            if (sshKey.isEmpty()) add("chiave SSH")
            if (useVpn) {
                if (vpnHost.isEmpty()) add("host VPN")
                if (vpnUser.isEmpty()) add("utente VPN")
                if (vpnPassword.isEmpty()) add("password VPN")
            }
        }

    val missing: List<String> get() = connectionMissing + listOfNotNull("repo GitHub".takeIf { repo.isEmpty() })

    val complete get() = connectionMissing.isEmpty()
}
