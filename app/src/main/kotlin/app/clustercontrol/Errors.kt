package app.clustercontrol

import app.clustercontrol.core.HelperMissingException
import app.clustercontrol.core.HostKeyChangedException
import app.clustercontrol.core.NoActiveJobException
import app.clustercontrol.sstp.SstpException
import com.jcraft.jsch.JSchException

enum class ErrorKind { AUTH, HELPER_MISSING, UNREACHABLE, CONFIG, OTHER }

data class ClusterError(val kind: ErrorKind, val message: String)

/** The action ran but its outcome is a failure (e.g. the cluster-side tick failed). */
class ActionFailed(message: String) : Exception(message)

class ConfigMissingException(missing: List<String> = emptyList()) :
    Exception("Credenziali non incorporate" + if (missing.isEmpty()) "" else ": manca " + missing.joinToString(", "))

private val AUTH_MARKERS = listOf("auth fail", "auth cancel", "userauth fail", "invalid privatekey", "passphrase")

/** Messages are Italian and never contain secrets (exception texts only). */
fun classify(e: Throwable): ClusterError {
    val chain = generateSequence(e) { it.cause }.toList()
    return when {
        chain.any { it is HostKeyChangedException } -> ClusterError(ErrorKind.AUTH, "Host key del cluster cambiata")
        chain.any { it is JSchException && AUTH_MARKERS.any { m -> it.message.orEmpty().lowercase().contains(m) } } ->
            ClusterError(ErrorKind.AUTH, "Autenticazione SSH fallita")
        chain.any { it is SstpException && it.message.orEmpty().lowercase().let { m -> "chap" in m || "auth" in m } } ->
            ClusterError(ErrorKind.AUTH, "Autenticazione VPN fallita")
        chain.any { it is HelperMissingException } -> ClusterError(ErrorKind.HELPER_MISSING, "Helper assente sul cluster")
        chain.any { it is ConfigMissingException } -> ClusterError(ErrorKind.CONFIG, chain.first { it is ConfigMissingException }.message!!)
        chain.any { it is NoActiveJobException } -> ClusterError(ErrorKind.OTHER, "Nessun job attivo")
        chain.any { it is JSchException || it is java.io.IOException } ->
            ClusterError(ErrorKind.UNREACHABLE, "Cluster non raggiungibile")
        else -> ClusterError(ErrorKind.OTHER, e.message ?: e.javaClass.simpleName)
    }
}
