package com.noxos.triggerrouter

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Extra information from the server's Drebin-permission model. ADVISORY ONLY: trained on 2012-era apps,
 * unvalidated on modern APKs. It must never gate quarantine, a verdict or an ACL decision; it is only shown.
 */
data class FileAdvisory(val verdict: String, val safetyScore: Float?, val reasoning: String) {
    /** The server's wording always starts "advisory only (...)"; guarantee it stays visible even if it ever doesn't. */
    val displayText: String
        get() = if (reasoning.startsWith(ADVISORY_PREFIX, ignoreCase = true)) reasoning else "$ADVISORY_PREFIX: $reasoning"

    companion object {
        const val ADVISORY_PREFIX = "advisory only"
    }
}

object FileAdvisoryClient {
    const val MAX_PERMISSIONS = 2000
    const val MAX_BODY_BYTES = 1024 * 1024
    private const val TIMEOUT_MS = 5_000

    /** The request body: deduplicated permission strings and nothing else, no identifiers of the file. */
    internal fun buildBody(permissions: List<String>): String? {
        val unique = permissions.filter { it.isNotBlank() }.distinct().take(MAX_PERMISSIONS)
        if (unique.isEmpty()) return null
        val body = unique.joinToString(prefix = "{\"permission_strings\":[", postfix = "]}", separator = ",") { JSONObject.quote(it) }
        return if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) null else body
    }

    /** Returns null on any failure, non-200, or a response that isn't marked advisory: callers must treat that as "no extra information". */
    fun request(endpoint: String, apiKey: String, permissions: List<String>): FileAdvisory? {
        val body = buildBody(permissions) ?: return null
        return try {
            val connection = URL("${endpoint.trim().trimEnd('/')}/analyze/file").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                if (connection.responseCode != 200) return null
                parse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    internal fun parse(response: String): FileAdvisory? {
        val json = try { JSONObject(response) } catch (e: Exception) { return null }
        if (!json.optBoolean("advisory", false)) return null
        val verdict = json.optString("verdict").takeIf { it.isNotBlank() } ?: return null
        val reasoning = json.optString("reasoning").takeIf { it.isNotBlank() } ?: return null
        val score = if (json.has("safety_score")) json.optDouble("safety_score").toFloat().takeUnless { it.isNaN() } else null
        return FileAdvisory(verdict, score, reasoning)
    }
}
