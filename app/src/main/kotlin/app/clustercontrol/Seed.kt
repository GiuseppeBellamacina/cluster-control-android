package app.clustercontrol

/** Defaults baked into this personal APK at build time (secrets.properties); empty when the file was absent. */
object Seed {
    const val vpnHost: String = BuildConfig.SEED_VPN_HOST
    const val vpnUser: String = BuildConfig.SEED_VPN_USER
    const val vpnPassword: String = BuildConfig.SEED_VPN_PASSWORD
    const val sshHost: String = BuildConfig.SEED_SSH_HOST
    const val sshUser: String = BuildConfig.SEED_SSH_USER
    const val sshKey: String = BuildConfig.SEED_SSH_KEY
}
