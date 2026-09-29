package com.noxos.triggerrouter.protocol

import java.nio.ByteBuffer

object VmPayloadProtocol {
    const val VSOCK_PORT = 5000L

    const val TASK_FILE_SCAN: Byte = 0

    const val TASK_NETWORK_SAMPLE: Byte = 1

    const val TASK_FILE_SCAN_WITH_META: Byte = 2

    const val MAX_PAYLOAD_BYTES = 150 * 1024 * 1024

    const val MAX_DECLARED_NAME_BYTES = 1024

    const val MAX_DECLARED_MIME_BYTES = 255

    /**
     * [u16 BE name_len][name UTF-8][u16 BE mime_len][mime]. A name/mime that doesn't fit the
     * guest's declared limits is sent empty rather than truncated (truncation could reshape a
     * multi-byte UTF-8 name into invalid bytes) - the guest treats an empty declared name/mime
     * as "not provided", same as a real caller with nothing to declare.
     */
    fun encodeFileScanMeta(name: String, mime: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).let { if (it.size <= MAX_DECLARED_NAME_BYTES) it else ByteArray(0) }
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII).let { if (it.size <= MAX_DECLARED_MIME_BYTES) it else ByteArray(0) }
        val buffer = ByteBuffer.allocate(2 + nameBytes.size + 2 + mimeBytes.size)
        buffer.putShort(nameBytes.size.toShort())
        buffer.put(nameBytes)
        buffer.putShort(mimeBytes.size.toShort())
        buffer.put(mimeBytes)
        return buffer.array()
    }

    /** True for the guest's exact "task 2 not supported yet" response (status 2, this one reason). */
    fun isUnknownTaskTypeError(status: Int, json: String): Boolean {
        if (status != 2) return false
        return try {
            org.json.JSONObject(json).optString("error") == "unknown task type"
        } catch (e: Exception) {
            false
        }
    }

    fun encodeRequest(taskType: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 4 + payload.size)
        buffer.put(taskType)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun encodeHeader(taskType: Byte, payloadLength: Int): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 4)
        buffer.put(taskType)
        buffer.putInt(payloadLength)
        return buffer.array()
    }

    fun encodePacketSamples(samples: List<ByteArray>): ByteArray {
        val totalLen = 2 + samples.sumOf { 4 + it.size }
        val buffer = ByteBuffer.allocate(totalLen)
        buffer.putShort(samples.size.toShort())
        samples.forEach { sample ->
            buffer.putInt(sample.size)
            buffer.put(sample)
        }
        return buffer.array()
    }

    fun decodeResponse(responseBytes: ByteArray): Response {
        if (responseBytes.size < 1) {
            throw IllegalArgumentException("Response too short")
        }
        val statusByte = responseBytes[0].toInt()
        val jsonString = String(responseBytes, 1, responseBytes.size - 1, Charsets.UTF_8)
        return Response(statusByte, jsonString)
    }

    data class Response(val status: Int, val json: String)
}
