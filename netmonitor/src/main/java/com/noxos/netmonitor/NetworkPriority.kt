package com.noxos.netmonitor

import com.noxos.audit.AclPriority

internal object NetworkPriority {
    private val STANDARD_PORTS = setOf(80, 443, 53)

    fun classify(port: Int?): AclPriority =
        if (port != null && port in STANDARD_PORTS) AclPriority.LOW else AclPriority.HIGH
}
