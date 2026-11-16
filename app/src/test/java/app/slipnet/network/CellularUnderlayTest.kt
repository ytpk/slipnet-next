package app.slipnet.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class CellularUnderlayTest {
    private lateinit var cm: ConnectivityManager
    private lateinit var mobile: Network
    private lateinit var underlay: CellularUnderlay
    private lateinit var callback: ConnectivityManager.NetworkCallback

    @Before fun setup() {
        cm = mock(ConnectivityManager::class.java)
        mobile = mock(Network::class.java)
        underlay = CellularUnderlay(cm)
        `when`(cm.getNetworkCapabilities(mobile)).thenReturn(mock(NetworkCapabilities::class.java))
        doAnswer { invocation ->
            callback = invocation.getArgument(1)
            callback.onAvailable(mobile)
            null
        }.`when`(cm).requestNetwork(any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java))
    }

    @After fun teardown() { underlay.release() }

    private fun withRequest(block: () -> Unit) {
        mockConstruction(NetworkRequest.Builder::class.java, withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            `when`(builder.build()).thenReturn(mock(NetworkRequest::class.java))
        }.use { construction ->
            block()
            if (construction.constructed().isNotEmpty()) {
                verify(construction.constructed().first()).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        }
    }

    @Test fun requestsCellularAndReleasesCallback() = withRequest {
        runBlocking { underlay.acquire {} }
        assertSame(mobile, underlay.network)
        assertTrue(CellularSocketBinding.available)
        verify(cm, never()).bindProcessToNetwork(any())
        underlay.release()
        verify(cm).unregisterNetworkCallback(callback)
        assertNull(CellularSocketBinding.network)
    }

    @Test fun cancellationReleasesPendingRequest() = withRequest {
        doAnswer { invocation -> callback = invocation.getArgument(1); null }
            .`when`(cm).requestNetwork(any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java))
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { underlay.acquire {} }
            job.cancelAndJoin()
        }
        verify(cm).unregisterNetworkCallback(callback)
        callback.onAvailable(mobile)
        assertNull(underlay.network)
    }

    @Test fun unavailableFailsWithoutFallback() = withRequest {
        doAnswer { invocation ->
            callback = invocation.getArgument(1)
            callback.onUnavailable()
            null
        }.`when`(cm).requestNetwork(any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java))
        assertThrows(IllegalStateException::class.java) { runBlocking { underlay.acquire {} } }
        verify(cm).unregisterNetworkCallback(callback)
        assertNull(CellularSocketBinding.network)
    }

    @Test fun lossKeepsDeadNetworkAndRefusesNewTraffic() = withRequest {
        var lost = 0
        runBlocking { underlay.acquire { lost++ } }
        callback.onLost(mock(Network::class.java))
        assertEquals(0, lost)
        callback.onLost(mobile)
        assertEquals(1, lost)
        assertSame(mobile, underlay.network)
        assertFalse(CellularSocketBinding.bind(123))
        assertThrows(IllegalStateException::class.java) { UnderlaySockets.resolve("example.test") }
        assertThrows(IllegalStateException::class.java) { UnderlaySockets.datagram() }
    }

    @Test fun lateCallbacksAfterReleaseAreIgnored() = withRequest {
        var lost = false
        runBlocking { underlay.acquire { lost = true } }
        underlay.release()
        callback.onAvailable(mobile)
        callback.onLost(mobile)
        assertNull(underlay.network)
        assertNull(CellularSocketBinding.network)
        assertFalse(lost)
    }

    @Test fun dnsUsesMobileAndDoesNotFallBackOnError() = withRequest {
        runBlocking { underlay.acquire {} }
        val address = InetAddress.getByName("203.0.113.7")
        `when`(mobile.getAllByName("example.test")).thenReturn(arrayOf(address))
        assertEquals(address, UnderlaySockets.resolve("example.test"))
        `when`(mobile.getAllByName("example.test")).thenThrow(java.net.UnknownHostException("mobile DNS failed"))
        assertThrows(java.net.UnknownHostException::class.java) { UnderlaySockets.resolve("example.test") }
    }

    @Test fun loopbackProxyRemainsReachableWithoutCellularSocketBinding() = withRequest {
        runBlocking { underlay.acquire {} }
        val loopback = InetAddress.getByName("127.0.0.1")
        `when`(mobile.getAllByName("127.0.0.1")).thenReturn(arrayOf(loopback))
        ServerSocket(0, 1, loopback).use { listener ->
            UnderlaySockets.createSocket().use { socket ->
                socket.connect(InetSocketAddress(loopback, listener.localPort), 1000)
                listener.accept().use { accepted ->
                    accepted.getOutputStream().write(42)
                    assertEquals(42, socket.getInputStream().read())
                }
            }
        }
        verify(mobile, never()).bindSocket(any(Socket::class.java))
    }

    @Test fun remoteTcpIsBoundBeforeConnect() = withRequest {
        runBlocking { underlay.acquire {} }
        val remote = InetAddress.getByName("203.0.113.7")
        `when`(mobile.getAllByName("203.0.113.7")).thenReturn(arrayOf(remote))
        UnderlaySockets.createSocket().use { socket ->
            runCatching { socket.connect(InetSocketAddress(remote, 1), 10) }
            verify(mobile).bindSocket(socket)
        }
    }

    @Test fun requestTimeoutReleasesCallback() = withRequest {
        underlay = CellularUnderlay(cm, requestTimeoutMillis = 10)
        doAnswer { invocation -> callback = invocation.getArgument(1); null }
            .`when`(cm).requestNetwork(any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java))
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            runBlocking { underlay.acquire {} }
        }
        verify(cm).unregisterNetworkCallback(callback)
        assertNull(CellularSocketBinding.network)
    }

    @Test fun lossDuringAcquisitionDoesNotPublishUsableRoute() = withRequest {
        doAnswer { invocation ->
            callback = invocation.getArgument(1)
            callback.onAvailable(mobile)
            callback.onLost(mobile)
            null
        }.`when`(cm).requestNetwork(any(NetworkRequest::class.java), any(ConnectivityManager.NetworkCallback::class.java))
        assertThrows(IllegalStateException::class.java) { runBlocking { underlay.acquire {} } }
        verify(cm).unregisterNetworkCallback(callback)
        assertNull(CellularSocketBinding.network)
    }

    @Test fun disabledModeUsesOrdinarySockets() {
        assertEquals(Socket::class.java, UnderlaySockets.createSocket().use { it.javaClass })
        assertTrue(CellularSocketBinding.bind(123))
        verifyNoInteractions(mobile)
    }
}
