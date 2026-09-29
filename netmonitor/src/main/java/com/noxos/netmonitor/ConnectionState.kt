package com.noxos.netmonitor

enum class TcpLifecycle { SYN_SEEN, ESTABLISHED, FIN, RST }

internal const val TCP_HANDSHAKE_WAIT_MILLIS = 12_000L
internal const val UDP_NO_REPLY_WAIT_MILLIS = 60_000L

/**
 * Approximation of the Argus `state` label the autoencoder was trained on (mapping agreed with
 * noxos-inference). Returns null while the state can't be decided yet, and the caller must then leave
 * the destination unscored rather than guess:
 * - TCP: SYN with no completed connect within [TCP_HANDSHAKE_WAIT_MILLIS] is REQ, established is CON,
 *   closed by a FIN is FIN, reset is RST.
 * - UDP: any reply is CON. No reply is null (unscored) until [UDP_NO_REPLY_WAIT_MILLIS] have passed,
 *   then INT. INT is 52% attack in training, so it must only be claimed once the silence is real.
 * - Anything else (ICMP is never relayed, so ECO is never produced) is "other".
 */
internal fun deriveConnectionState(
    protocol: String?,
    tcpLifecycle: TcpLifecycle?,
    inboundChunks: Long,
    ageMillis: Long
): String? = when (protocol?.uppercase()) {
    "TCP" -> when (tcpLifecycle) {
        null -> null
        TcpLifecycle.SYN_SEEN -> if (ageMillis >= TCP_HANDSHAKE_WAIT_MILLIS) "REQ" else null
        TcpLifecycle.ESTABLISHED -> "CON"
        TcpLifecycle.FIN -> "FIN"
        TcpLifecycle.RST -> "RST"
    }
    "UDP" -> when {
        inboundChunks > 0 -> "CON"
        ageMillis >= UDP_NO_REPLY_WAIT_MILLIS -> "INT"
        else -> null
    }
    else -> "other"
}
