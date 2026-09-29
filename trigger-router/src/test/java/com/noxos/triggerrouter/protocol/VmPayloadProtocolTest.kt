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
    fun testEncodeFileScanMetaFramesNameAndMimeWithU16BigEndianLengths() {
        val encoded = VmPayloadProtocol.encodeFileScanMeta("a.pdf", "application/pdf")

        val expected = ByteBuffer.allocate(2 + 5 + 2 + 15)
            .putShort(5).put("a.pdf".toByteArray(Charsets.UTF_8))
            .putShort(15).put("application/pdf".toByteArray(Charsets.US_ASCII))
            .array()
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testEncodeFileScanMetaAllowsEmptyNameAndMime() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), VmPayloadProtocol.encodeFileScanMeta("", ""))
    }

    @Test
    fun testEncodeFileScanMetaCountsUtf8BytesNotCharacters() {
        val encoded = VmPayloadProtocol.encodeFileScanMeta("é.jpg", "")

        assertEquals(6, ByteBuffer.wrap(encoded).short.toInt())
    }

    @Test
    fun testEncodeFileScanMetaSendsAnOversizedNameOrMimeAsEmptyInsteadOfTruncating() {
        val encoded = VmPayloadProtocol.encodeFileScanMeta(
            "n".repeat(VmPayloadProtocol.MAX_DECLARED_NAME_BYTES + 1),
            "m".repeat(VmPayloadProtocol.MAX_DECLARED_MIME_BYTES + 1)
        )

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), encoded)
    }

    @Test
    fun testEncodeFileScanMetaKeepsANameExactlyAtTheLimit() {
        val encoded = VmPayloadProtocol.encodeFileScanMeta("n".repeat(VmPayloadProtocol.MAX_DECLARED_NAME_BYTES), "")

        assertEquals(VmPayloadProtocol.MAX_DECLARED_NAME_BYTES, ByteBuffer.wrap(encoded).short.toInt())
    }

    @Test
    fun testEncodeHeaderForTask2UsesTheNewTaskTypeByte() {
        val header = VmPayloadProtocol.encodeHeader(VmPayloadProtocol.TASK_FILE_SCAN_WITH_META, 1300)

        assertArrayEquals(ByteBuffer.allocate(5).put(2.toByte()).putInt(1300).array(), header)
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
