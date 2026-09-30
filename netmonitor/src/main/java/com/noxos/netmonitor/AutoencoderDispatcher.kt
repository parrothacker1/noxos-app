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
import com.noxos.audit.WardenSettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class AutoencoderDispatcher(
    context: Context,
    private val aclRepository: AclRepository,
    private val settings: WardenSettingsRepository? = null
) {
    private val featureLog = FeatureLog.get(context)
    private val loggedThisRun = HashSet<String>()

    private class Snapshot(val flow: AclEntry, val state: String?, val ageMillis: Long)

    private val modelUpdateManager = ModelUpdateManager(
        context,
        manifestUrl = BuildConfig.AUTOENCODER_MANIFEST_URL,
        modelName = "autoencoder"
    )

    suspend fun run() {
        while (true) {
            // Nothing else downloads this model: fetch it as soon as it is missing, then refresh daily.
            if (modelUpdateManager.hasLocalModel()) modelUpdateManager.checkForUpdateIfStale() else modelUpdateManager.ensureModelLoaded()

            val loggingEnabled = settings?.featureLogEnabled?.first() ?: false
            aclRepository.nextAutoencoderBatch(BATCH_SIZE).forEach { entry ->
                val stats = NetMonitorService.connectionStats[entry.subject]
                val snapshot = stats?.let { snapshotOf(entry, it, System.currentTimeMillis()) }
                if (loggingEnabled && snapshot != null) logSnapshot(entry.subject, snapshot)

                val verdict = evaluate(entry, snapshot) ?: run {
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

    private fun snapshotOf(entry: AclEntry, stats: ConnectionStats, now: Long): Snapshot {
        val age = now - stats.firstSeenAtEpochMillis
        return Snapshot(
            flow = entry.withLiveStats(stats, now),
            state = deriveConnectionState(entry.protocol, stats.tcpLifecycle, stats.dstPacketCount.get(), age),
            ageMillis = age
        )
    }

    // The snapshot is the very object the model is scored on, so the log holds exactly what it saw.
    private fun logSnapshot(subject: String, snapshot: Snapshot) {
        val state = snapshot.state ?: return
        if (subject in loggedThisRun) return
        val record = buildFeatureLogRecord(snapshot.flow, state, snapshot.ageMillis) ?: return
        featureLog.append(subject, record)
        loggedThisRun.add(subject)
    }

    private fun evaluate(entry: AclEntry, snapshot: Snapshot?): AutoencoderVerdict? {
        val modelJson = modelUpdateManager.loadCurrentModelJson() ?: return null
        return try {
            val autoencoder = OnDeviceAutoencoder(modelJson)
            val uncaptured = (autoencoder.numericFeatureNames - CAPTURED_NUMERIC_FEATURES) +
                (autoencoder.categoricalFeatureNames - CAPTURED_CATEGORICAL_FEATURES)
            if (uncaptured.isNotEmpty()) {
                Log.w(TAG, "model needs inputs Warden doesn't capture yet $uncaptured, leaving ${entry.subject} pending")
                return null
            }

            // Without live stats (e.g. the process restarted) the persisted row can't be trusted as the
            // model's input, so don't score it.
            val live = snapshot ?: return null
            val categorical = mutableMapOf<String, String?>("proto" to entry.protocol)
            if ("state" in autoencoder.categoricalFeatureNames) {
                categorical["state"] = live.state ?: return null
            }
            autoencoder.evaluate(networkFlowFeatures(live.flow) { null }, categorical)
        } catch (e: Exception) {
            Log.w(TAG, "autoencoder evaluation failed for ${entry.subject}", e)
            null
        }
    }

    companion object {
        private const val TAG = "WardenAutoencoderDispatcher"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val BATCH_SIZE = 50
    }
}
