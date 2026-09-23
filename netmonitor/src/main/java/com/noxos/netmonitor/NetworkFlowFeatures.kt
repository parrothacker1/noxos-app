package com.noxos.netmonitor

import com.noxos.audit.AclEntry

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
