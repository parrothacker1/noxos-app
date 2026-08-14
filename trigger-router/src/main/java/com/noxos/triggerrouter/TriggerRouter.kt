package com.noxos.triggerrouter

import android.content.Context
import android.net.Uri
import com.noxos.audit.AuditEvent
import com.noxos.audit.AuditEventType
import com.noxos.audit.AuditOutcome
import com.noxos.audit.AuditRepository
import com.noxos.triggerrouter.protocol.VmPayloadProtocol
import com.noxos.triggerrouter.vm.VmSessionFactory
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class TriggerRouter(
    private val context: Context,
    private val auditRepository: AuditRepository,
    private val vmSessionFactory: VmSessionFactory
) {

    suspend fun scanFile(fileUri: Uri, inputDescriptor: String): ScanResult {
        val startTime = System.currentTimeMillis()
        var outcome = AuditOutcome.ERROR
        var resultSummary: String? = null
        var errorMessage: String? = null

        try {
            val fileBytes = readFileBytes(fileUri)
            if (fileBytes == null) {
                val err = "Could not read file bytes from URI"
                outcome = AuditOutcome.FAILURE
                errorMessage = err
                return ScanResult.Failure(err)
            }

            val scanResult = withTimeout(10000L) {
                vmSessionFactory.createSession(context).use { session ->
                    val transport = session.getTransport()
                    val requestPayload = VmPayloadProtocol.encodeRequest(fileBytes)
                    transport.send(requestPayload)

                    val responsePayload = transport.receive()
                    val decoded = VmPayloadProtocol.decodeResponse(responsePayload)

                    when (decoded.status) {
                        0 -> {
                            val json = JSONObject(decoded.json)
                            val metadata = mutableMapOf<String, String>()
                            json.keys().forEach { key ->
                                metadata[key] = json.optString(key, "")
                            }
                            outcome = AuditOutcome.SUCCESS
                            resultSummary = "Parsed EXIF successfully"
                            ScanResult.Success(ExifData(metadata))
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
                }
            }
            return scanResult
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            outcome = AuditOutcome.ERROR
            errorMessage = "Scan timed out (10s limit)"
            return ScanResult.Error(errorMessage!!)
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
                errorMessage = errorMessage
            )
            auditRepository.record(auditEvent)
        }
    }

    private fun readFileBytes(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val byteBuffer = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var len: Int
                while (inputStream.read(buffer).also { len = it } != -1) {
                    byteBuffer.write(buffer, 0, len)
                }
                byteBuffer.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }
}
