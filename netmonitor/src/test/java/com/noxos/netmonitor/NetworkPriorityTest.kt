package com.noxos.netmonitor

import com.noxos.audit.AclPriority
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPriorityTest {

    @Test
    fun `standard ports are low priority`() {
        assertEquals(AclPriority.LOW, NetworkPriority.classify(443))
        assertEquals(AclPriority.LOW, NetworkPriority.classify(80))
        assertEquals(AclPriority.LOW, NetworkPriority.classify(53))
    }

    @Test
    fun `non-standard ports are high priority`() {
        assertEquals(AclPriority.HIGH, NetworkPriority.classify(31337))
        assertEquals(AclPriority.HIGH, NetworkPriority.classify(8081))
    }

    @Test
    fun `unknown port defaults to high priority`() {
        assertEquals(AclPriority.HIGH, NetworkPriority.classify(null))
    }
}
