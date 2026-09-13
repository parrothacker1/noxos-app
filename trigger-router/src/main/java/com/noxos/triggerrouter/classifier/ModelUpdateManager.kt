package com.noxos.triggerrouter.classifier

import android.content.Context
import com.noxos.triggerrouter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class ModelUpdateResult {
    object UpToDate : ModelUpdateResult()
    data class Updated(val newVersion: Int) : ModelUpdateResult()
    data class Failed(val reason: String) : ModelUpdateResult()
}

class ModelUpdateManager(
    private val context: Context,
    private val manifestUrl: String = BuildConfig.MODEL_MANIFEST_URL
) {
    private val modelFile: File get() = File(context.filesDir, MODEL_FILE_NAME)
    private val metaFile: File get() = File(context.filesDir, META_FILE_NAME)

    fun loadCurrentModelJson(): String {
        return if (modelFile.exists()) {
            modelFile.readText()
        } else {
            context.assets.open(BUNDLED_ASSET_NAME).bufferedReader().use { it.readText() }
        }
    }

    suspend fun checkForUpdateIfStale(): ModelUpdateResult = withContext(Dispatchers.IO) {
        val meta = readMeta()
        val now = System.currentTimeMillis()
        if (now - meta.lastCheckedAtEpochMillis < CHECK_INTERVAL_MS) {
            return@withContext ModelUpdateResult.UpToDate
        }
        checkForUpdate(meta, now)
    }

    private fun checkForUpdate(meta: Meta, now: Long): ModelUpdateResult {
        val manifestJson = httpGetText(manifestUrl)
            ?: return fail(meta, now, "manifest unreachable")
        val manifest = parseManifest(manifestJson)
            ?: return fail(meta, now, "malformed manifest")

        if (manifest.version <= meta.version) {
            writeMeta(meta.copy(lastCheckedAtEpochMillis = now))
            return ModelUpdateResult.UpToDate
        }

        val modelBytes = httpGetBytes(manifest.modelUrl)
            ?: return fail(meta, now, "model download failed")

        val actualSha256 = sha256Hex(modelBytes)
        if (!actualSha256.equals(manifest.sha256, ignoreCase = true)) {
            return fail(meta, now, "checksum mismatch: expected ${manifest.sha256}, got $actualSha256")
        }

        modelFile.writeBytes(modelBytes)
        writeMeta(Meta(manifest.version, now))
        return ModelUpdateResult.Updated(manifest.version)
    }

    private fun fail(meta: Meta, now: Long, reason: String): ModelUpdateResult.Failed {
        writeMeta(meta.copy(lastCheckedAtEpochMillis = now))
        return ModelUpdateResult.Failed(reason)
    }

    private data class Meta(val version: Int, val lastCheckedAtEpochMillis: Long)

    private fun readMeta(): Meta {
        if (!metaFile.exists()) return Meta(0, 0)
        val fields = metaFile.readLines().mapNotNull { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        return Meta(
            version = fields["version"]?.toIntOrNull() ?: 0,
            lastCheckedAtEpochMillis = fields["lastCheckedAtEpochMillis"]?.toLongOrNull() ?: 0
        )
    }

    private fun writeMeta(meta: Meta) {
        metaFile.writeText("version=${meta.version}\nlastCheckedAtEpochMillis=${meta.lastCheckedAtEpochMillis}\n")
    }

    private data class Manifest(val version: Int, val sha256: String, val modelUrl: String)

    private fun parseManifest(json: String): Manifest? {
        return try {
            val obj = JSONObject(json)
            Manifest(
                version = obj.getInt("version"),
                sha256 = obj.getString("sha256"),
                modelUrl = obj.getString("modelUrl")
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MODEL_FILE_NAME = "on_device_network_model.json"
        private const val META_FILE_NAME = "on_device_network_model.meta"
        private const val BUNDLED_ASSET_NAME = "on_device_network_model.json"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val TIMEOUT_MS = 10_000

        private fun httpGetText(url: String): String? {
            return try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (connection.responseCode != 200) return null
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }
        }

        private fun httpGetBytes(url: String): ByteArray? {
            return try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (connection.responseCode != 200) return null
                connection.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
