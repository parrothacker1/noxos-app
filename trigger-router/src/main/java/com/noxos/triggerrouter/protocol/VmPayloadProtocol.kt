package com.noxos.triggerrouter.protocol

import java.nio.ByteBuffer

object VmPayloadProtocol {
    const val VSOCK_PORT = 5000L

    fun encodeRequest(fileBytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(4 + fileBytes.size)
        buffer.putInt(fileBytes.size)
        buffer.put(fileBytes)
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
