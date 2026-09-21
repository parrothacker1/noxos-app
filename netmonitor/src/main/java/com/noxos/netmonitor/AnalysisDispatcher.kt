package com.noxos.netmonitor

import android.content.Context
import android.util.Log
import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclRepository
import com.noxos.audit.WardenSettingsRepository
import com.noxos.triggerrouter.classifier.ClassifierVerdict
import com.noxos.triggerrouter.classifier.ModelUpdateManager
import com.noxos.triggerrouter.classifier.OnDeviceNetworkClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class AnalysisDispatcher(
    context: Context,
    private val aclRepository: AclRepository,
    private val settingsRepository: WardenSettingsRepository
) {
    private val modelUpdateManager = ModelUpdateManager(context)

    suspend fun run() {
        while (true) {
            val endpoint = settingsRepository.inferenceEndpointUrl.first().trim().trimEnd('/')
            val aiEnabled = settingsRepository.aiNetworkAnalysisEnabled.first()

            aclRepository.nextAnalysisBatch(BATCH_SIZE).forEach { entry ->
                resolve(entry, endpoint, aiEnabled)
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun resolve(entry: AclEntry, endpoint: String, aiEnabled: Boolean) {
        val onDeviceVerdict = withContext(Dispatchers.IO) { classifyOnDevice(entry) }
        if (onDeviceVerdict != null && onDeviceVerdict.verdict == "allow") {
            aclRepository.allow(
                AclKind.NETWORK, entry.subject,
                "on-device: looks benign",
                onDeviceVerdict.safetyScore, sessionOnly = true
            )
            return
        }

        if (endpoint.isBlank() || !aiEnabled) return
        val apiKey = settingsRepository.inferenceApiKey.first()
        val response = withContext(Dispatchers.IO) { requestVerdict(endpoint, apiKey, entry) } ?: return
        val reason = response.reasoning?.let { "ai: $it" } ?: "ai: ${response.verdict}"
        when (response.verdict) {
            "allow" -> aclRepository.allow(AclKind.NETWORK, entry.subject, reason, response.safetyScore, sessionOnly = true)
            "block" -> aclRepository.block(AclKind.NETWORK, entry.subject, reason, response.safetyScore, sessionOnly = true)
        }
    }

    private fun classifyOnDevice(entry: AclEntry): ClassifierVerdict? {
        val modelJson = modelUpdateManager.loadCurrentModelJson() ?: return null
        return try {
            val classifier = OnDeviceNetworkClassifier(modelJson)
            classifier.classify(networkFlowFeatures(entry, classifier))
        } catch (e: Exception) {
            Log.w(TAG, "on-device classification failed for ${entry.subject}", e)
            null
        }
    }

    internal data class NetworkVerdict(val verdict: String, val reasoning: String?, val safetyScore: Float?)

    companion object {
        private const val TAG = "WardenAnalysisDispatcher"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val BATCH_SIZE = 10
        private const val TIMEOUT_MS = 10_000
        private val VERDICT_FIELD = Regex(""""verdict"\s*:\s*"([a-zA-Z_]+)"""")
        private val REASONING_FIELD = Regex(""""reasoning"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val SAFETY_SCORE_FIELD = Regex(""""safety_score"\s*:\s*([0-9]*\.?[0-9]+)""")

        private fun jsonEscaped(value: String) =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun jsonUnescaped(value: String) =
            value.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")

        internal fun networkFlowFeatures(entry: AclEntry, classifier: OnDeviceNetworkClassifier): Map<String, Float> {
            val features = mutableMapOf<String, Float>()
            entry.destPort?.let { features["dst_port"] = it.toFloat() }
            entry.srcByteCount?.let { features["src_byte_count"] = it.toFloat() }
            entry.srcPacketCount?.let { features["src_packet_count"] = it.toFloat() }
            entry.dstByteCount?.let { features["dst_byte_count"] = it.toFloat() }
            entry.dstPacketCount?.let { features["dst_packet_count"] = it.toFloat() }
            entry.durationMillis?.let { features["duration_millis"] = it.toFloat() }
            entry.handshakeLatencyMillis?.let { features["handshake_latency_millis"] = it.toFloat() }
            val srcPackets = entry.srcPacketCount
            val srcBytes = entry.srcByteCount
            if (srcPackets != null && srcPackets > 0 && srcBytes != null) {
                features["smean"] = srcBytes.toFloat() / srcPackets
            }
            val dstPackets = entry.dstPacketCount
            val dstBytes = entry.dstByteCount
            if (dstPackets != null && dstPackets > 0 && dstBytes != null) {
                features["dmean"] = dstBytes.toFloat() / dstPackets
            }
            entry.protocol?.lowercase()?.let { proto ->
                classifier.encodeCategory("proto", proto)?.let { features["proto"] = it }
            }
            return features
        }

        internal fun requestVerdict(endpoint: String, apiKey: String, entry: AclEntry): NetworkVerdict? {
            return try {
                val connection = URL("$endpoint/analyze/network").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")

                val body = buildString {
                    append("{")
                    append("\"ip\":${jsonEscaped(entry.subject)},")
                    append("\"priority\":${jsonEscaped(entry.priority.name)},")
                    append("\"reason\":${jsonEscaped(entry.reason)},")
                    append("\"first_flagged_epoch_millis\":${entry.updatedAtEpochMillis}")
                    when (entry.protocol) {
                        "TCP" -> append(",\"proto\":\"tcp\"")
                        "UDP" -> append(",\"proto\":\"udp\"")
                    }
                    entry.destPort?.let { append(",\"dst_port\":$it") }
                    append("}")
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (connection.responseCode != 200) {
                    Log.w(TAG, "analyze/network for ${entry.subject} returned HTTP ${connection.responseCode}")
                    return null
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val verdict = VERDICT_FIELD.find(response)?.groupValues?.get(1) ?: return null
                val reasoning = REASONING_FIELD.find(response)?.groupValues?.get(1)?.let(::jsonUnescaped)
                val safetyScore = SAFETY_SCORE_FIELD.find(response)?.groupValues?.get(1)?.toFloatOrNull()
                NetworkVerdict(verdict, reasoning, safetyScore)
            } catch (e: Exception) {
                Log.w(TAG, "analyze/network request failed for ${entry.subject}", e)
                null
            }
        }
    }
}
