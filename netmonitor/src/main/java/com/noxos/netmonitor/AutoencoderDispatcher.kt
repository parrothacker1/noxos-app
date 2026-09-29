package com.noxos.netmonitor

import android.content.Context
import android.util.Log
import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclRepository
import com.noxos.triggerrouter.BuildConfig
import com.noxos.triggerrouter.classifier.AutoencoderVerdict
import com.noxos.triggerrouter.classifier.ModelUpdateManager
import com.noxos.triggerrouter.classifier.OnDeviceAutoencoder
import kotlinx.coroutines.delay

class AutoencoderDispatcher(
    context: Context,
    private val aclRepository: AclRepository
) {
    private val modelUpdateManager = ModelUpdateManager(
        context,
        manifestUrl = BuildConfig.AUTOENCODER_MANIFEST_URL,
        modelName = "autoencoder"
    )

    suspend fun run() {
        while (true) {
            aclRepository.nextAutoencoderBatch(BATCH_SIZE).forEach { entry ->
                val stats = NetMonitorService.connectionStats[entry.subject]
                val verdict = evaluate(entry, stats) ?: run {
                    Log.w(TAG, "no autoencoder verdict available, leaving ${entry.subject} pending")
                    return@forEach
                }

                if (stats != null) {
                    NetMonitorService.connectionStats.remove(entry.subject)
                    aclRepository.recordConnectionStats(
                        AclKind.NETWORK, entry.subject,
                        stats.srcPacketCount.get(), stats.srcByteCount.get(),
                        stats.dstPacketCount.get(), stats.dstByteCount.get(),
                        System.currentTimeMillis() - stats.firstSeenAtEpochMillis,
                        stats.handshakeLatencyMillis
                    )
                }
                if (verdict.anomalous) {
                    aclRepository.markAutoencoderFlagged(
                        AclKind.NETWORK, entry.subject,
                        "autoencoder: flagged (reconstruction error ${verdict.reconstructionError}), escalating to student"
                    )
                } else {
                    aclRepository.allow(AclKind.NETWORK, entry.subject, "autoencoder: normal", sessionOnly = true)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun evaluate(entry: AclEntry, stats: ConnectionStats?): AutoencoderVerdict? {
        val modelJson = modelUpdateManager.loadCurrentModelJson() ?: return null
        return try {
            val autoencoder = OnDeviceAutoencoder(modelJson)
            val uncaptured = (autoencoder.numericFeatureNames - CAPTURED_NUMERIC_FEATURES) +
                (autoencoder.categoricalFeatureNames - CAPTURED_CATEGORICAL_FEATURES)
            if (uncaptured.isNotEmpty()) {
                Log.w(TAG, "model needs inputs Warden doesn't capture yet $uncaptured, leaving ${entry.subject} pending")
                return null
            }

            val now = System.currentTimeMillis()
            val flow = if (stats != null) entry.withLiveStats(stats, now) else entry
            val categorical = mutableMapOf<String, String?>("proto" to entry.protocol)
            if ("state" in autoencoder.categoricalFeatureNames) {
                // Without live stats (e.g. the process restarted) the state can't be derived; don't guess.
                val liveStats = stats ?: return null
                categorical["state"] = deriveConnectionState(
                    entry.protocol, liveStats.tcpLifecycle,
                    liveStats.dstPacketCount.get(), now - liveStats.firstSeenAtEpochMillis
                ) ?: return null
            }
            autoencoder.evaluate(networkFlowFeatures(flow) { null }, categorical)
        } catch (e: Exception) {
            Log.w(TAG, "autoencoder evaluation failed for ${entry.subject}", e)
            null
        }
    }

    companion object {
        private const val TAG = "WardenAutoencoderDispatcher"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val BATCH_SIZE = 5
    }
}
