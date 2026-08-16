package com.noxos.netmonitor

internal object PacketUtils {

    fun readUShort(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    fun writeUShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun ipBytes(packet: ByteArray, offset: Int): ByteArray =
        byteArrayOf(packet[offset], packet[offset + 1], packet[offset + 2], packet[offset + 3])

    fun formatIp(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}" +
               ".${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }

    fun ipChecksum(header: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            sum += readUShort(header, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    fun buildUdpPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray, payloadOffset: Int, payloadLen: Int
    ): ByteArray {
        val udpLen = 8 + payloadLen
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        out[1] = 0
        writeUShort(out, 2, totalLen)
        writeUShort(out, 4, 0)
        writeUShort(out, 6, 0)
        out[8] = 64
        out[9] = 17
        writeUShort(out, 10, 0)
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        writeUShort(out, 10, ipChecksum(out, 20))

        writeUShort(out, 20, srcPort)
        writeUShort(out, 22, dstPort)
        writeUShort(out, 24, udpLen)
        writeUShort(out, 26, 0)
        System.arraycopy(payload, payloadOffset, out, 28, payloadLen)

        return out
    }

    fun extractFlowDescriptor(packet: ByteArray, len: Int): String? {
        if (len < 20) return null

        val protocol = packet[9].toInt() and 0xFF
        val protoName = when (protocol) {
            6   -> "TCP"
            17  -> "UDP"
            1   -> "ICMP"
            else -> "IP/$protocol"
        }

        val srcIp = formatIp(packet, 12)
        val dstIp = formatIp(packet, 16)

        if (len >= 24 && (protocol == 6 || protocol == 17)) {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl + 4 <= len) {
                val srcPort = readUShort(packet, ihl)
                val dstPort = readUShort(packet, ihl + 2)
                return "$protoName $srcIp:$srcPort → $dstIp:$dstPort"
            }
        }

        return "$protoName $srcIp → $dstIp"
    }
}
