package com.noxos.triggerrouter.protocol

import java.nio.ByteBuffer

object VmPayloadProtocol {
    const val VSOCK_PORT = 5000L

    /** Parse the request payload as an image file and return its EXIF metadata (or a parse error). */
    const val TASK_FILE_SCAN: Byte = 0

    /** Run a header-sanity/protocol-conformance check on a raw packet sample and return a flagged verdict. */
    const val TASK_NETWORK_SAMPLE: Byte = 1

    fun encodeRequest(taskType: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 4 + payload.size)
        buffer.put(taskType)
        buffer.putInt(payload.size)
        buffer.put(payload)
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
