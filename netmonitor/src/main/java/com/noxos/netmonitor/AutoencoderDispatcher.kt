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
                NetMonitorService.connectionStats.remove(entry.subject)?.let { stats ->
                    aclRepository.recordConnectionStats(
                        AclKind.NETWORK, entry.subject,
                        stats.srcPacketCount.get(), stats.srcByteCount.get(),
                        stats.dstPacketCount.get(), stats.dstByteCount.get(),
                        System.currentTimeMillis() - stats.firstSeenAtEpochMillis,
                        stats.handshakeLatencyMillis
                    )
                }

                val verdict = evaluate(entry) ?: run {
                    Log.w(TAG, "no autoencoder model loaded yet, leaving ${entry.subject} pending")
                    return@forEach
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

    private fun evaluate(entry: AclEntry): AutoencoderVerdict? {
        val modelJson = modelUpdateManager.loadCurrentModelJson() ?: return null
        return try {
            val autoencoder = OnDeviceAutoencoder(modelJson)
            val features = networkFlowFeatures(entry) { autoencoder.encodeCategory("proto", it) }
            autoencoder.evaluate(features)
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
