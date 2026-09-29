package com.noxos.netmonitor

import com.noxos.audit.AclEntry

internal val CAPTURED_NUMERIC_FEATURES = setOf(
    "dst_port", "src_byte_count", "src_packet_count", "dst_byte_count", "dst_packet_count",
    "duration_millis", "handshake_latency_millis", "smean", "dmean"
)

internal val CAPTURED_CATEGORICAL_FEATURES = setOf("proto", "state")

internal fun AclEntry.withLiveStats(stats: ConnectionStats, nowMillis: Long): AclEntry = copy(
    srcPacketCount = stats.srcPacketCount.get(),
    srcByteCount = stats.srcByteCount.get(),
    dstPacketCount = stats.dstPacketCount.get(),
    dstByteCount = stats.dstByteCount.get(),
    durationMillis = nowMillis - stats.firstSeenAtEpochMillis,
    handshakeLatencyMillis = stats.handshakeLatencyMillis
)

internal fun networkFlowFeatures(entry: AclEntry, encodeProto: (String) -> Float?): Map<String, Float> {
    val features = mutableMapOf<String, Float>()
    entry.destPort?.let { features["dst_port"] = it.toFloat() }
    entry.srcByteCount?.let { features["src_byte_count"] = it.toFloat() }
    entry.srcPacketCount?.let { features["src_packet_count"] = it.toFloat() }
    entry.dstByteCount?.let { features["dst_byte_count"] = it.toFloat() }
    entry.dstPacketCount?.let { features["dst_packet_count"] = it.toFloat() }
    entry.durationMillis?.let { features["duration_millis"] = it.toFloat() }
    entry.handshakeLatencyMillis?.let { features["handshake_latency_millis"] = it.toFloat() }
    val srcPackets = entry.srcPacketCount
    val srcBytes = entry.srcByteCount
    if (srcPackets != null && srcPackets > 0 && srcBytes != null) {
        features["smean"] = srcBytes.toFloat() / srcPackets
    }
    val dstPackets = entry.dstPacketCount
    val dstBytes = entry.dstByteCount
    if (dstPackets != null && dstPackets > 0 && dstBytes != null) {
        features["dmean"] = dstBytes.toFloat() / dstPackets
    }
    entry.protocol?.lowercase()?.let { proto ->
        encodeProto(proto)?.let { features["proto"] = it }
    }
    return features
}
