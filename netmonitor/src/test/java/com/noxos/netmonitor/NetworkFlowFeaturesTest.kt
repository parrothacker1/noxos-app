package com.noxos.netmonitor

import com.noxos.audit.AclEntry
import com.noxos.audit.AclKind
import com.noxos.audit.AclPriority
import com.noxos.audit.AclState
import com.noxos.triggerrouter.classifier.OnDeviceNetworkClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NetworkFlowFeaturesTest {

    private val modelJson = """
        {
          "base_score": 0.0,
          "feature_defaults": {},
          "categories": { "proto": ["icmp", "tcp", "udp"] },
          "trees": []
        }
    """.trimIndent()

    private val classifier = OnDeviceNetworkClassifier(modelJson)
    private val encodeProto: (String) -> Float? = { classifier.encodeCategory("proto", it) }

    private fun entry(
        protocol: String? = "TCP",
        destPort: Int? = 443,
        srcByteCount: Long? = 1000L,
        srcPacketCount: Long? = 10L,
        dstByteCount: Long? = 2000L,
        dstPacketCount: Long? = 20L,
        durationMillis: Long? = 500L,
        handshakeLatencyMillis: Long? = 12L
    ) = AclEntry(
        kind = AclKind.NETWORK,
        subject = "203.0.113.7",
        state = AclState.FLAGGED,
        priority = AclPriority.HIGH,
        reason = "new destination, pending analysis",
        updatedAtEpochMillis = 1_700_000_000_000L,
        destPort = destPort,
        protocol = protocol,
        srcByteCount = srcByteCount,
        srcPacketCount = srcPacketCount,
        dstByteCount = dstByteCount,
        dstPacketCount = dstPacketCount,
        durationMillis = durationMillis,
        handshakeLatencyMillis = handshakeLatencyMillis
    )

    @Test
    fun `maps AclEntry's captured stats to the model's real snake_case feature names`() {
        val features = networkFlowFeatures(entry(), encodeProto)

        assertEquals(443f, features["dst_port"])
        assertEquals(1000f, features["src_byte_count"])
        assertEquals(10f, features["src_packet_count"])
        assertEquals(2000f, features["dst_byte_count"])
        assertEquals(20f, features["dst_packet_count"])
        assertEquals(500f, features["duration_millis"])
        assertEquals(12f, features["handshake_latency_millis"])
    }

    @Test
    fun `derives smean and dmean as byte-to-packet ratios, not the dataset's own precomputed fields`() {
        val features = networkFlowFeatures(entry(), encodeProto)

        assertEquals(100f, features["smean"])
        assertEquals(100f, features["dmean"])
    }

    @Test
    fun `encodes protocol using the model's own label-encoded category index, not the raw string`() {
        val tcpFeatures = networkFlowFeatures(entry(protocol = "TCP"), encodeProto)
        val udpFeatures = networkFlowFeatures(entry(protocol = "UDP"), encodeProto)

        assertEquals(1f, tcpFeatures["proto"])
        assertEquals(2f, udpFeatures["proto"])
    }

    @Test
    fun `an unrecognized protocol omits proto entirely rather than guessing an index`() {
        val features = networkFlowFeatures(entry(protocol = "OTHER"), encodeProto)

        assertNull(features["proto"])
    }

    @Test
    fun `zero packet counts omit the derived means instead of dividing by zero`() {
        val features = networkFlowFeatures(entry(srcPacketCount = 0L, dstPacketCount = 0L), encodeProto)

        assertNull(features["smean"])
        assertNull(features["dmean"])
    }

    @Test
    fun `missing stats are omitted rather than sent as zero, so the classifier falls back to its own defaults`() {
        val features = networkFlowFeatures(
            entry(
                srcByteCount = null, srcPacketCount = null,
                dstByteCount = null, dstPacketCount = null,
                durationMillis = null, handshakeLatencyMillis = null
            ),
            encodeProto
        )

        assertNull(features["src_byte_count"])
        assertNull(features["smean"])
        assertNull(features["dmean"])
    }
}
