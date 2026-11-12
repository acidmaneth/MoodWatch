package com.moodwatch.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wear OS keeps Wi-Fi and LTE powered down while idle or off the phone, so a missing or broken
 * default network doesn't mean the watch is offline. When the default network can't do the job,
 * this asks the system to bring up cellular (then any transport) and runs the fetch on it.
 */
object MoodNetwork {
    private const val TAG = "MoodNetwork"
    private const val REQUEST_TIMEOUT_MS = 30_000

    /** Runs [block] on the first network that works. [block] gets null for the default network. */
    suspend fun <T> withInternet(context: Context, block: suspend (Network?) -> T): T {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var lastError: IOException? = null

        if (hasDefaultInternet(cm)) {
            try {
                return block(null)
            } catch (e: MoodNotFoundException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Default network fetch failed: ${e.message}")
                lastError = e
            }
        } else {
            Log.i(TAG, "No default network")
        }

        // Ask for cellular and any transport at once; whichever comes up first is used.
        val available = CompletableDeferred<Network?>()
        val pending = AtomicInteger(2)
        val callbacks = listOf(NetworkCapabilities.TRANSPORT_CELLULAR, null).map { transport ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .apply { if (transport != null) addTransportType(transport) }
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    available.complete(network)
                }

                override fun onUnavailable() {
                    if (pending.decrementAndGet() == 0) available.complete(null)
                }
            }
            cm.requestNetwork(request, callback, REQUEST_TIMEOUT_MS)
            callback
        }
        try {
            val network = available.await()
                ?: throw lastError ?: IOException("No network available")
            Log.i(TAG, "Fetching over requested network $network")
            return block(network)
        } finally {
            callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    private fun hasDefaultInternet(cm: ConnectivityManager): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
