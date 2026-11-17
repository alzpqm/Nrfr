package com.github.nrfr

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.telephony.TelephonyNetworkSpecifier
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.github.nrfr.manager.PrivilegedCarrierConfigRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Shell-only, explicit-subscription probe; Wi-Fi stays available for wireless ADB. */
@TargetApi(31)
class CellularDiagnosticActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = intent.getIntExtra("slot", -1)
        val label = intent.getStringExtra("label").orEmpty()
        if (slot !in 0..1 || !label.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) { finish(); return }
        lifecycleScope.launch {
            val report = JSONObject().put("slot", slot).put("capturedAt", System.currentTimeMillis())
            try {
                val reply = PrivilegedCarrierConfigRunner.run(this@CellularDiagnosticActivity,
                    PrivilegedCarrierConfigRunner.Request.ReadSimCards)
                @Suppress("DEPRECATION")
                val cards = reply.getParcelableArrayList<Bundle>(PrivilegedCarrierConfigRunner.RESULT_SIM_CARDS).orEmpty()
                val card = cards.first { it.getInt("slot") == slot + 1 }
                probe(card.getInt("sub_id"), report)
            } catch (error: Exception) {
                report.put("error", error.javaClass.simpleName)
            }
            val dir = checkNotNull(getExternalFilesDir("cellular-research"))
            File(dir, "$label.json").writeText(report.toString())
            finish()
        }
    }

    private suspend fun probe(subId: Int, report: JSONObject) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val available = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available.complete(network) }
            override fun onUnavailable() { available.completeExceptionally(IllegalStateException("cellular_unavailable")) }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build()).build()
        report.put("requestedSubId", subId)
        connectivity.requestNetwork(request, callback, 12_000)
        try {
            val network = withTimeout(13_000) { available.await() }
            val capabilities = checkNotNull(connectivity.getNetworkCapabilities(network))
            check(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            check(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            val observed = capabilities.subscriptionIds
            report.put("networkHandle", network.networkHandle).put("cellular", true)
                .put("subscriptionVerified", observed.contains(subId))
                .put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            check(observed.isEmpty() || observed.contains(subId))
            withContext(Dispatchers.IO) {
                val executor = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "nrfr-cellular-probe").apply { isDaemon = true }
                }
                val future = executor.submit<JSONObject> {
                    val outcome = JSONObject()
                    try {
                        val addresses = network.getAllByName("connectivitycheck.gstatic.com")
                        outcome.put("dns", if (addresses.isNotEmpty()) "ok" else "empty")
                        val connection = network.openConnection(URL("https://connectivitycheck.gstatic.com/generate_204")) as HttpURLConnection
                        try {
                            connection.connectTimeout = 6_000
                            connection.readTimeout = 6_000
                            connection.instanceFollowRedirects = false
                            val status = connection.responseCode
                            outcome.put("httpsStatus", status).put("https", if (status == 204) "ok" else "unexpected_status")
                        } finally { connection.disconnect() }
                    } catch (error: Exception) { outcome.put("probeError", error.javaClass.simpleName) }
                    outcome
                }
                try {
                    val outcome = future.get(18, TimeUnit.SECONDS)
                    outcome.keys().forEach { name -> report.put(name, outcome.get(name)) }
                }
                catch (_: Exception) { future.cancel(true); report.put("probeDeadline", true) }
                finally { executor.shutdownNow() }
            }
        } finally { connectivity.unregisterNetworkCallback(callback) }
    }
}
