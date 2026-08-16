package com.noxos.netmonitor

internal object BlockedHostChecker {
    fun isBlocked(destIp: String, blockedHosts: Set<String>): Boolean = destIp in blockedHosts
}
