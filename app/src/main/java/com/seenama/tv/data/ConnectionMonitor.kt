package com.seenama.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

enum class ServerHealth { Online, Degraded, Offline }

data class ConnectionState(
    /** Device has a validated internet connection. */
    val network: Boolean = true,
    /** Last API request outcome; `null` before the first request. */
    val apiOk: Boolean? = null,
    /** Requests are currently being retried or a mirror switch is in progress. */
    val recovering: Boolean = false,
    val endpoint: String = "",
    /** Rolling average round-trip of recent API calls. */
    val latencyMs: Long? = null,
) {
    val health: ServerHealth
        get() = when {
            !network -> ServerHealth.Offline
            apiOk == false -> ServerHealth.Offline
            recovering || (latencyMs ?: 0) > 2500 -> ServerHealth.Degraded
            else -> ServerHealth.Online
        }

    /** Short host label, e.g. "world" for world.hana-api.com. */
    val serverName: String
        get() = endpoint.substringAfter("://").substringBefore('/').substringBefore('.')
}

/**
 * Single source of truth for "can we talk to the backend": device connectivity
 * (via ConnectivityManager callbacks) plus the outcome and latency of API calls.
 */
class ConnectionMonitor(context: Context) {
    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    init {
        _state.update { it.copy(network = currentlyOnline()) }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
                },
            )
        }
    }

    private val _reconnects = MutableStateFlow(0)
    /** Increments every time connectivity comes back; images use it to retry failed loads. */
    val reconnects: StateFlow<Int> = _reconnects.asStateFlow()

    fun refresh() {
        val was = _state.value.network
        val now = currentlyOnline()
        _state.update { it.copy(network = now) }
        if (!was && now) _reconnects.update { it + 1 }
    }

    /** Debug builds: simulate losing the network (see MainActivity / DevHooks). */
    @Volatile var simulateOffline = false
        set(v) { field = v; refresh() }

    private fun currentlyOnline(): Boolean = !simulateOffline && runCatching {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    fun onSuccess(latencyMs: Long, endpoint: String) = _state.update {
        val avg = it.latencyMs?.let { prev -> (prev * 3 + latencyMs) / 4 } ?: latencyMs
        it.copy(apiOk = true, recovering = false, latencyMs = avg, endpoint = endpoint)
    }

    fun onFailure() = _state.update { it.copy(apiOk = false, recovering = false) }
    fun onRecovering() = _state.update { it.copy(recovering = true) }
    fun onEndpoint(endpoint: String) = _state.update { it.copy(endpoint = endpoint, latencyMs = null) }

    /** Suspends until the device is online again (or [timeoutMs] passes). */
    suspend fun awaitNetwork(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it.network } } != null
}
