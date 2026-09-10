package com.noxos.netmonitor

import com.noxos.audit.AclState

internal object AclChecker {
    fun verdict(destIp: String, acl: Map<String, AclState>): AclState? = acl[destIp]
}
