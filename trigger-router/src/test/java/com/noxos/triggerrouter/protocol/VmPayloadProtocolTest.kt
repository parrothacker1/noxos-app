package com.noxos.triggerrouter.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class VmPayloadProtocolTest {

    @Test
    fun testEncodeRequestForFileScan() {
        val input = byteArrayOf(1, 2, 3, 4)
        val encoded = VmPayloadProtocol.encodeRequest(VmPayloadProtocol.TASK_FILE_SCAN, input)

        val expected = ByteBuffer.allocate(9).put(0.toByte()).putInt(4).put(input).array()
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testEncodeRequestForNetworkSampleUsesADifferentTaskTypeByte() {
        val input = byteArrayOf(9, 9)
        val encoded = VmPayloadProtocol.encodeRequest(VmPayloadProtocol.TASK_NETWORK_SAMPLE, input)

        val expected = ByteBuffer.allocate(7).put(1.toByte()).putInt(2).put(input).array()
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testEncodePacketSamplesFramesEachPacketWithItsOwnLengthPrefix() {
        val outbound = byteArrayOf(1, 2, 3)
        val inbound = byteArrayOf(9, 8)
        val encoded = VmPayloadProtocol.encodePacketSamples(listOf(outbound, inbound))

        val expected = ByteBuffer.allocate(2 + 4 + 3 + 4 + 2)
            .putShort(2)
            .putInt(3).put(outbound)
            .putInt(2).put(inbound)
            .array()
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testEncodePacketSamplesHandlesASingleSample() {
        val encoded = VmPayloadProtocol.encodePacketSamples(listOf(byteArrayOf(5, 5)))

        val expected = ByteBuffer.allocate(2 + 4 + 2).putShort(1).putInt(2).put(byteArrayOf(5, 5)).array()
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testDecodeResponse() {
        val json = "{\"key\":\"value\"}"
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val responseBytes = ByteArray(1 + jsonBytes.size)
        responseBytes[0] = 0.toByte()
        System.arraycopy(jsonBytes, 0, responseBytes, 1, jsonBytes.size)

        val decoded = VmPayloadProtocol.decodeResponse(responseBytes)
        assertEquals(0, decoded.status)
        assertEquals(json, decoded.json)
    }
}
