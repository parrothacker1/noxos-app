package com.noxos.triggerrouter.protocol

import java.nio.ByteBuffer

object VmPayloadProtocol {
    const val VSOCK_PORT = 5000L

    const val TASK_FILE_SCAN: Byte = 0

    const val TASK_NETWORK_SAMPLE: Byte = 1

    fun encodeRequest(taskType: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 4 + payload.size)
        buffer.put(taskType)
        buffer.putInt(payload.size)
        buffer.put(payload)
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
