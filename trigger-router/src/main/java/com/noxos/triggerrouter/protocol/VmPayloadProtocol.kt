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

    /**
     * [TASK_NETWORK_SAMPLE]'s payload shape: a small, self-contained list of raw packets, not
     * always exactly one - a newly-flagged destination's sample can include both the outbound
     * packet that caused the flag and the first inbound reply, captured separately (see
     * NetMonitorService.pendingPacketSamples). Framing: `[2-byte unsigned count][per packet:
     * 4-byte length][packet bytes]...`. Note the packets themselves aren't uniform: the outbound
     * one is a full captured IPv4 packet (as seen on the tun interface); an inbound UDP reply is
     * bare UDP payload bytes only (as received from the real OS socket - no synthetic IP/UDP
     * header reconstructed), while an inbound TCP reply is the raw bytes read from the relayed
     * socket's InputStream (also no synthetic header). The guest-side check needs to handle a
     * mixed list, not assume every entry is a parseable IPv4 packet.
     */
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
