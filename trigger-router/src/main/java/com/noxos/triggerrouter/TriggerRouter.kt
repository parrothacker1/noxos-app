package com.noxos.triggerrouter

import android.content.Context
import android.net.Uri
import com.noxos.audit.AuditEvent
import com.noxos.audit.AuditEventType
import com.noxos.audit.AuditOutcome
import com.noxos.audit.AuditRepository
import com.noxos.audit.WardenSettingsRepository
import com.noxos.triggerrouter.protocol.VmPayloadProtocol
import com.noxos.triggerrouter.vm.VmSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class TriggerRouter(
    private val context: Context,
    private val auditRepository: AuditRepository,
    private val vmSessionFactory: VmSessionFactory,
    private val settingsRepository: WardenSettingsRepository
) {

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress

    suspend fun scanFile(fileUri: Uri, inputDescriptor: String): ScanResult {
        val startTime = System.currentTimeMillis()
        var outcome = AuditOutcome.ERROR
        var resultSummary: String? = null
        var errorMessage: String? = null
        val stepDurations = mutableMapOf<ScanStep, Long>()

        fun enter(step: ScanStep) {
            _progress.value = ScanProgress(step, inputDescriptor, stepDurations.toMap())
        }

        try {
            val knownLength = statFileLength(fileUri)
            var bufferedBytes: ByteArray? = null

            if (knownLength != null) {
                if (knownLength > VmPayloadProtocol.MAX_PAYLOAD_BYTES) {
                    val err = "File too large (${knownLength / (1024 * 1024)} MB, " +
                        "max ${VmPayloadProtocol.MAX_PAYLOAD_BYTES / (1024 * 1024)} MB)"
                    outcome = AuditOutcome.FAILURE
                    errorMessage = err
                    return ScanResult.Failure(err)
                }
            } else {
                bufferedBytes = readFileBytesBounded(fileUri)
                if (bufferedBytes == null) {
                    val err = "Could not read file, or it exceeds the " +
                        "${VmPayloadProtocol.MAX_PAYLOAD_BYTES / (1024 * 1024)} MB scan limit"
                    outcome = AuditOutcome.FAILURE
                    errorMessage = err
                    return ScanResult.Failure(err)
                }
            }

            val timeoutMillis = settingsRepository.vmSessionTimeoutSeconds.first() * 1000L
            val mimeType = context.contentResolver.getType(fileUri) ?: ""
            val metaPrefix = VmPayloadProtocol.encodeFileScanMeta(inputDescriptor, mimeType)

            suspend fun attempt(useDeclaredMeta: Boolean): VmPayloadProtocol.Response = withTimeout(timeoutMillis) {
                vmSessionFactory.createSession(context).use { session ->
                    enter(ScanStep.BOOTING)
                    var stepStart = System.currentTimeMillis()
                    val transport = session.getTransport()
                    stepDurations[ScanStep.BOOTING] = System.currentTimeMillis() - stepStart
                    enter(ScanStep.EXECUTING)
                    stepStart = System.currentTimeMillis()

                    val taskType = if (useDeclaredMeta) VmPayloadProtocol.TASK_FILE_SCAN_WITH_META else VmPayloadProtocol.TASK_FILE_SCAN
                    val prefix = if (useDeclaredMeta) metaPrefix else ByteArray(0)
                    if (knownLength != null) {
                        val header = VmPayloadProtocol.encodeHeader(taskType, prefix.size + knownLength.toInt()) + prefix
                        val fileStream = context.contentResolver.openInputStream(fileUri)
                            ?: throw java.io.IOException("Could not open file for scanning")
                        fileStream.use { transport.sendStream(header, it, knownLength) }
                    } else {
                        val header = VmPayloadProtocol.encodeHeader(taskType, prefix.size + bufferedBytes!!.size)
                        transport.send(header + prefix + bufferedBytes)
                    }
                    val responsePayload = transport.receive()

                    stepDurations[ScanStep.EXECUTING] = System.currentTimeMillis() - stepStart
                    VmPayloadProtocol.decodeResponse(responsePayload)
                }
            }

            val scanResult = run {
                var decoded = attempt(useDeclaredMeta = true)
                if (VmPayloadProtocol.isUnknownTaskTypeError(decoded.status, decoded.json)) {
                    // Deployed guest predates task-2 support (noxos-payload < v1.0.4) - retry the
                    // same file as a plain task-0 scan rather than failing a perfectly good file.
                    decoded = attempt(useDeclaredMeta = false)
                }

                enter(ScanStep.SANITIZING)
                var stepStart = System.currentTimeMillis()

                var advisoryPermissions: List<String>? = null
                val result = when (decoded.status) {
                    0 -> {
                        val json = JSONObject(decoded.json)
                        if (json.optBoolean("cheap_filter_flagged", false)) {
                            val reason = json.optString("cheap_filter_reason", "flagged by in-VM pre-scan filter")
                            outcome = AuditOutcome.FAILURE
                            errorMessage = reason
                            ScanResult.Failure(reason)
                        } else {
                            val metadata = mutableMapOf<String, String>()
                            json.keys().forEach { key ->
                                if (key != "cheap_filter_flagged" && key != "cheap_filter_reason" && key != "permissions") {
                                    metadata[key] = json.optString(key, "")
                                }
                            }
                            if (metadata["file_type"] == "apk") {
                                advisoryPermissions = json.optJSONArray("permissions")?.let { arr ->
                                    (0 until arr.length()).map { arr.optString(it) }
                                }
                            }
                            outcome = AuditOutcome.SUCCESS
                            resultSummary = if (metadata.keys == setOf("file_type")) {
                                "Unrecognized file type (${metadata["file_type"]}) — not analyzed, not flagged"
                            } else {
                                "Scan completed, no issues found"
                            }
                            ScanResult.Success(ExifData(metadata))
                        }
                    }
                    1 -> {
                        outcome = AuditOutcome.FAILURE
                        errorMessage = decoded.json
                        ScanResult.Failure("Parse error: ${decoded.json}")
                    }
                    2 -> {
                        outcome = AuditOutcome.FAILURE
                        errorMessage = decoded.json
                        ScanResult.Failure("Malformed input: ${decoded.json}")
                    }
                    else -> {
                        outcome = AuditOutcome.ERROR
                        errorMessage = "Unknown protocol status: ${decoded.status}"
                        ScanResult.Error(errorMessage!!)
                    }
                }

                // Advisory only: extra information for a clean APK, never an input to outcome or quarantine.
                val finalResult = if (result is ScanResult.Success && !advisoryPermissions.isNullOrEmpty()) {
                    fetchAdvisory(advisoryPermissions)?.let { advisory ->
                        resultSummary = "${resultSummary ?: "Scan completed"} | Advisory: ${advisory.displayText}"
                        result.copy(advisory = advisory)
                    } ?: result
                } else {
                    result
                }

                stepDurations[ScanStep.SANITIZING] = System.currentTimeMillis() - stepStart
                enter(ScanStep.DESTROYING)
                finalResult
            }
            return scanResult
        } catch (e: TimeoutCancellationException) {
            outcome = AuditOutcome.ERROR
            errorMessage = "Scan timed out"
            return ScanResult.Error(errorMessage!!)
        } catch (e: CancellationException) {
            outcome = AuditOutcome.ERROR
            errorMessage = "Scan cancelled"
            throw e
        } catch (e: Exception) {
            outcome = AuditOutcome.ERROR
            errorMessage = e.message ?: e.toString()
            return ScanResult.Error(errorMessage!!)
        } finally {
            val duration = System.currentTimeMillis() - startTime
            val auditEvent = AuditEvent(
                timestampEpochMillis = startTime,
                eventType = AuditEventType.FILE_SCAN,
                inputDescriptor = inputDescriptor,
                outcome = outcome,
                resultSummary = resultSummary,
                durationMillis = duration,
                errorMessage = errorMessage,
                stepTimingsCsv = ScanProgress(stepDurationsMillis = stepDurations).toCsv().ifEmpty { null }
            )
            withContext(NonCancellable) {
                auditRepository.record(auditEvent)
            }
            enter(ScanStep.DONE)
        }
    }

    private suspend fun fetchAdvisory(permissions: List<String>): FileAdvisory? {
        return try {
            if (!settingsRepository.aiFileAnalysisEnabled.first()) return null
            val endpoint = settingsRepository.inferenceEndpointUrl.first().trim()
            if (endpoint.isBlank()) return null
            val apiKey = settingsRepository.inferenceApiKey.first()
            withContext(Dispatchers.IO) { FileAdvisoryClient.request(endpoint, apiKey, permissions) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun statFileLength(uri: Uri): Long? {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readFileBytesBounded(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val byteBuffer = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var len: Int
                while (inputStream.read(buffer).also { len = it } != -1) {
                    total += len
                    if (total > VmPayloadProtocol.MAX_PAYLOAD_BYTES) return null
                    byteBuffer.write(buffer, 0, len)
                }
                byteBuffer.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }
}
