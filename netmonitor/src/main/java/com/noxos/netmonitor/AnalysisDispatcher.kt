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
        applyResult(aclRepository, entry, withContext(Dispatchers.IO) { requestVerdict(endpoint, apiKey, entry) })
    }

    private fun classifyOnDevice(entry: AclEntry): ClassifierVerdict? {
        val modelJson = modelUpdateManager.loadCurrentModelJson() ?: return null
        return try {
            val classifier = OnDeviceNetworkClassifier(modelJson)
            val features = networkFlowFeatures(entry) { classifier.encodeCategory("proto", it) }
            classifier.classify(features)
        } catch (e: Exception) {
            Log.w(TAG, "on-device classification failed for ${entry.subject}", e)
            null
        }
    }

    internal sealed class NetworkVerdictResult {
        data class Verdict(val verdict: String, val reasoning: String?, val safetyScore: Float?) : NetworkVerdictResult()

        data class Rejected(val status: Int, val missing: List<String>, val invalid: List<String>) : NetworkVerdictResult() {
            fun describe(): String =
                if (status == 0) "not sent, local features missing $missing" else "HTTP $status, missing $missing, invalid $invalid"
        }

        data class Unavailable(val reason: String) : NetworkVerdictResult()
    }

    companion object {
        private const val TAG = "WardenAnalysisDispatcher"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val BATCH_SIZE = 10
        private const val TIMEOUT_MS = 10_000
        private val VERDICT_FIELD = Regex(""""verdict"\s*:\s*"([a-zA-Z_]+)"""")
        private val REASONING_FIELD = Regex(""""reasoning"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val SAFETY_SCORE_FIELD = Regex(""""safety_score"\s*:\s*([0-9]*\.?[0-9]+)""")

        internal suspend fun applyResult(acl: AclRepository, entry: AclEntry, result: NetworkVerdictResult) {
            when (result) {
                is NetworkVerdictResult.Verdict -> {
                    val reason = result.reasoning?.let { "ai: $it" } ?: "ai: ${result.verdict}"
                    when (result.verdict) {
                        "allow" -> acl.allow(AclKind.NETWORK, entry.subject, reason, result.safetyScore, sessionOnly = true)
                        "block" -> acl.block(AclKind.NETWORK, entry.subject, reason, result.safetyScore, sessionOnly = true)
                        else -> holdForUser(acl, entry, "ai: uncertain verdict, blocked until you decide")
                    }
                }
                is NetworkVerdictResult.Rejected -> {
                    Log.w(TAG, "analyze/network for ${entry.subject} rejected (HTTP ${result.status}) missing=${result.missing} invalid=${result.invalid}")
                    holdForUser(acl, entry, "ai: no verdict (${result.describe()}), blocked until you decide")
                }
                is NetworkVerdictResult.Unavailable -> Log.w(TAG, "analyze/network for ${entry.subject} unavailable: ${result.reason}")
            }
        }

        private suspend fun holdForUser(acl: AclRepository, entry: AclEntry, reason: String) {
            acl.block(AclKind.NETWORK, entry.subject, reason, null, sessionOnly = true)
        }

        private fun jsonEscaped(value: String) =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun jsonUnescaped(value: String) =
            value.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")

        private val REQUIRED_NUMERIC = listOf(
            "dst_port", "src_byte_count", "src_packet_count", "dst_byte_count", "dst_packet_count", "duration_millis"
        )
        private val MISSING_FIELD = Regex(""""missing"\s*:\s*\[([^\]]*)\]""")
        private val INVALID_FIELD = Regex(""""invalid"\s*:\s*\[([^\]]*)\]""")
        private val QUOTED = Regex(""""([^"]*)"""")

        private fun jsonNumber(value: Float): String =
            if (value == value.toLong().toFloat() && Math.abs(value) < 1e15f) value.toLong().toString() else value.toString()

        private fun names(field: Regex, json: String): List<String> =
            field.find(json)?.groupValues?.get(1)?.let { list -> QUOTED.findAll(list).map { it.groupValues[1] }.toList() } ?: emptyList()

        internal fun requestBody(entry: AclEntry): Pair<String?, List<String>> {
            val features = networkFlowFeatures(entry) { null }
            val proto = entry.protocol?.lowercase()?.takeIf { it == "tcp" || it == "udp" }
            val missing = REQUIRED_NUMERIC.filter { it !in features } + listOfNotNull("proto".takeIf { proto == null })
            if (missing.isNotEmpty()) return null to missing
            val body = buildString {
                append("{")
                append("\"ip\":${jsonEscaped(entry.subject)},")
                append("\"priority\":${jsonEscaped(entry.priority.name)},")
                append("\"reason\":${jsonEscaped(entry.reason)},")
                append("\"first_flagged_epoch_millis\":${entry.updatedAtEpochMillis},")
                append("\"proto\":\"$proto\"")
                features.forEach { (name, value) -> append(",\"$name\":${jsonNumber(value)}") }
                append("}")
            }
            return body to emptyList()
        }

        internal fun requestVerdict(endpoint: String, apiKey: String, entry: AclEntry, timeoutMillis: Int = TIMEOUT_MS): NetworkVerdictResult {
            val (body, missing) = requestBody(entry)
            if (body == null) return NetworkVerdictResult.Rejected(0, missing, emptyList())
            return try {
                val connection = URL("$endpoint/analyze/network").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                if (status != 200) {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    return when (status) {
                        400, 401, 422 -> NetworkVerdictResult.Rejected(status, names(MISSING_FIELD, errorBody), names(INVALID_FIELD, errorBody))
                        else -> NetworkVerdictResult.Unavailable("HTTP $status")
                    }
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val verdict = VERDICT_FIELD.find(response)?.groupValues?.get(1)
                if (verdict !in setOf("allow", "block", "uncertain")) return NetworkVerdictResult.Unavailable("no usable verdict in the response")
                val reasoning = REASONING_FIELD.find(response)?.groupValues?.get(1)?.let(::jsonUnescaped)
                val safetyScore = SAFETY_SCORE_FIELD.find(response)?.groupValues?.get(1)?.toFloatOrNull()
                NetworkVerdictResult.Verdict(verdict!!, reasoning, safetyScore)
            } catch (e: Exception) {
                NetworkVerdictResult.Unavailable(e.javaClass.simpleName)
            }
        }
    }
}
