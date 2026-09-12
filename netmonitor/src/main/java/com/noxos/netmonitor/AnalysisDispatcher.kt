package com.noxos.netmonitor

import android.util.Log
import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclRepository
import com.noxos.audit.WardenSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class AnalysisDispatcher(
    private val aclRepository: AclRepository,
    private val settingsRepository: WardenSettingsRepository
) {

    suspend fun run() {
        while (true) {
            val endpoint = settingsRepository.inferenceEndpointUrl.first().trim().trimEnd('/')
            val aiEnabled = settingsRepository.aiAnalysisEnabled.first()

            if (endpoint.isNotBlank() && aiEnabled) {
                val apiKey = settingsRepository.inferenceApiKey.first()
                aclRepository.nextAnalysisBatch(BATCH_SIZE).forEach { entry ->
                    resolve(endpoint, apiKey, entry)
                }
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun resolve(endpoint: String, apiKey: String, entry: AclEntry) {
        val verdict = withContext(Dispatchers.IO) { requestVerdict(endpoint, apiKey, entry) }
        when (verdict) {
            "allow" -> aclRepository.allow(AclKind.NETWORK, entry.subject, "ai: allowed")
            "block" -> aclRepository.block(AclKind.NETWORK, entry.subject, "ai: blocked")
        }
    }

    companion object {
        private const val TAG = "WardenAnalysisDispatcher"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val BATCH_SIZE = 10
        private const val TIMEOUT_MS = 10_000
        private val VERDICT_FIELD = Regex(""""verdict"\s*:\s*"([a-zA-Z_]+)"""")

        private fun jsonEscaped(value: String) =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        internal fun requestVerdict(endpoint: String, apiKey: String, entry: AclEntry): String? {
            return try {
                val connection = URL("$endpoint/analyze/network").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")

                val body = "{" +
                    "\"ip\":${jsonEscaped(entry.subject)}," +
                    "\"priority\":${jsonEscaped(entry.priority.name)}," +
                    "\"reason\":${jsonEscaped(entry.reason)}," +
                    "\"first_flagged_epoch_millis\":${entry.updatedAtEpochMillis}" +
                    "}"
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (connection.responseCode != 200) {
                    Log.w(TAG, "analyze/network for ${entry.subject} returned HTTP ${connection.responseCode}")
                    return null
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                VERDICT_FIELD.find(response)?.groupValues?.get(1) ?: "uncertain"
            } catch (e: Exception) {
                Log.w(TAG, "analyze/network request failed for ${entry.subject}", e)
                null
            }
        }
    }
}
