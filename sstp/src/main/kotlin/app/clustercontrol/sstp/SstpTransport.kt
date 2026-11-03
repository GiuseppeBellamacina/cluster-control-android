package app.clustercontrol.sstp

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/** Opens TCP connections to hosts reachable inside the VPN, without any system VPN device. */
class SstpTransport(
    private val vpnHost: String,
    private val user: String,
    private val password: String,
    private val vpnPort: Int = 443,
    private val pinSha256: String? = null,
    private val onStage: (name: String, detail: String) -> Unit = { _, _ -> },
) {
    /** [host] must be an IPv4 literal or a name resolvable on the normal network; one tunnel per socket. */
    fun openSocket(host: String, port: Int): Socket {
        val addr = InetAddress.getByName(host)
        val tunnel = SstpTunnel(vpnHost, user, password, vpnPort, pinSha256, true, onStage)
        try {
            tunnel.connect()
            return TunnelSocket(tunnel, tunnel.openTcp(addr, port), addr, port)
        } catch (e: Exception) {
            tunnel.close()
            throw e
        }
    }
}

/** java.net.Socket facade over a [TcpFlow]; closing it tears the tunnel down. */
internal class TunnelSocket(
    private val tunnel: SstpTunnel,
    private val flow: TcpFlow,
    private val addr: InetAddress,
    private val remotePort: Int,
) : Socket() {
    private var timeoutMs = 0
    private var closed = false

    private val input = object : InputStream() {
        override fun read() = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int) = if (len == 0) 0 else flow.read(b, off, len, timeoutMs)
    }
    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = flow.write(b, off, len)
    }

    override fun getInputStream() = input
    override fun getOutputStream() = output
    override fun isConnected() = true
    override fun isClosed() = closed
    override fun isBound() = true
    override fun isInputShutdown() = closed
    override fun isOutputShutdown() = closed
    override fun shutdownOutput() = flow.shutdownOutput()
    override fun shutdownInput() {}
    override fun getInetAddress(): InetAddress = addr
    override fun getPort() = remotePort
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(addr, remotePort)
    override fun connect(endpoint: SocketAddress?) = throw UnsupportedOperationException("already connected")
    override fun connect(endpoint: SocketAddress?, timeout: Int) = throw UnsupportedOperationException("already connected")
    override fun setSoTimeout(timeout: Int) { timeoutMs = timeout }
    override fun getSoTimeout() = timeoutMs
    override fun setTcpNoDelay(on: Boolean) {}
    override fun getTcpNoDelay() = true
    override fun setKeepAlive(on: Boolean) {}
    override fun getKeepAlive() = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { flow.close() }
        tunnel.close()
        super.close()
    }
}
