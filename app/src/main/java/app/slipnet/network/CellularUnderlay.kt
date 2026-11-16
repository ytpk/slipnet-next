package app.slipnet.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Session-owned cellular request. Wi-Fi is never disabled or disconnected. */
class CellularUnderlay(
    private val cm: ConnectivityManager,
    private val requestTimeoutMillis: Long = 30_000
) {
    @Volatile private var active = false
    @Volatile private var lost = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile var network: Network? = null
        private set

    suspend fun acquire(onLost: () -> Unit) {
        check(callback == null)
        active = true
        lost = false
        val ready = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(candidate: Network) {
                // Pin this session to one network. A replacement requires a fresh session.
                if (active && network == null && !ready.isCompleted) {
                    network = candidate
                    ready.complete(candidate)
                }
            }
            override fun onLost(candidate: Network) {
                if (active && candidate == network) {
                    // Retain the dead network binding until teardown. Never fall back to Wi-Fi.
                    lost = true
                    CellularSocketBinding.available = false
                    onLost()
                }
            }
            override fun onUnavailable() {
                ready.completeExceptionally(IllegalStateException("Mobile data unavailable. Enable mobile data and reconnect."))
            }
        }
        callback = cb
        try {
            cm.requestNetwork(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), cb)
            val selected = withTimeout(requestTimeoutMillis) { ready.await() }
            CellularSocketBinding.network = selected
            CellularSocketBinding.available = !lost && cm.getNetworkCapabilities(selected) != null
            check(CellularSocketBinding.available) { "Mobile network was lost during connection" }
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Call only after stopping tunnel workers. */
    fun release() {
        active = false
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        CellularSocketBinding.network = null
        CellularSocketBinding.available = true
    }
}

/** Explicit binding AFTER VpnService.protect(), before connect()/send(). */
object CellularSocketBinding {
    @Volatile internal var network: Network? = null
    val isEnabled: Boolean get() = network != null
    @Volatile internal var available = true

    fun bind(fd: Int): Boolean {
        val selected = network ?: return true
        if (!available) return false
        return try {
            // dup() prevents Kotlin from taking ownership of a native worker's descriptor.
            ParcelFileDescriptor.fromFd(fd).use { selected.bindSocket(it.fileDescriptor) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
