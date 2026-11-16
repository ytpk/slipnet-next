package app.slipnet.network

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** Outbound sockets only. Listening and accepted LAN sockets retain normal Wi-Fi routing. */
object UnderlaySockets : SocketFactory() {
    override fun createSocket(): Socket {
        val network = CellularSocketBinding.network ?: return Socket()
        return object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                check(CellularSocketBinding.available) { "Mobile network unavailable" }
                val address = endpoint as? InetSocketAddress
                    ?: throw IllegalArgumentException("Internet socket address required")
                val resolved = InetSocketAddress(network.getAllByName(address.hostString).first(), address.port)
                // Inter-layer connections stay on loopback; never bind LAN listeners.
                if (!resolved.address.isLoopbackAddress) network.bindSocket(this)
                super.connect(resolved, timeout)
            }
            override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
        }
    }

    private fun connectedSocket(host: String, port: Int, local: InetSocketAddress? = null): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(address(host, port))
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    override fun createSocket(host: String, port: Int): Socket = connectedSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connectedSocket(host, port, InetSocketAddress(local, localPort))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress!!, port, local, localPort)

    fun datagram(): DatagramSocket = DatagramSocket().also { socket ->
        try {
            CellularSocketBinding.network?.let {
                check(CellularSocketBinding.available) { "Mobile network unavailable" }
                it.bindSocket(socket)
            }
        } catch (e: Exception) { socket.close(); throw e }
    }

    fun resolveAll(host: String): Array<InetAddress> {
        val network = CellularSocketBinding.network ?: return InetAddress.getAllByName(host)
        check(CellularSocketBinding.available) { "Mobile network unavailable" }
        return network.getAllByName(host)
    }
    fun resolve(host: String): InetAddress = resolveAll(host).first()
    fun address(host: String, port: Int): InetSocketAddress = InetSocketAddress(resolve(host), port)
    fun address(host: InetAddress, port: Int): InetSocketAddress = InetSocketAddress(host, port)
    fun address(port: Int): InetSocketAddress = InetSocketAddress(port)
}
