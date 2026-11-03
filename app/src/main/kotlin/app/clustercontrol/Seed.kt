package app.clustercontrol

/** Credentials baked into this personal APK at build time (secrets.properties); empty when the file was absent. */
object Seed {
    const val vpnHost: String = BuildConfig.SEED_VPN_HOST
    const val vpnUser: String = BuildConfig.SEED_VPN_USER
    const val vpnPassword: String = BuildConfig.SEED_VPN_PASSWORD
    const val sshHost: String = BuildConfig.SEED_SSH_HOST
    const val sshUser: String = BuildConfig.SEED_SSH_USER
    const val sshKey: String = BuildConfig.SEED_SSH_KEY

    const val REPO = "GiuseppeBellamacina/neuro_symbolic_t2g"
    const val BRANCH = "main"
    const val SSH_PORT = 22

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
