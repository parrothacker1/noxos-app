package com.noxos.netmonitor

import com.noxos.audit.AclState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AclCheckerTest {

    @Test
    fun `returns null for an ip with no acl entry`() {
        assertNull(AclChecker.verdict("1.2.3.4", emptyMap()))
    }

    @Test
    fun `returns the stored state for a known ip`() {
        val acl = mapOf("1.2.3.4" to AclState.BLOCKED, "5.6.7.8" to AclState.ALLOWED)
        assertEquals(AclState.BLOCKED, AclChecker.verdict("1.2.3.4", acl))
        assertEquals(AclState.ALLOWED, AclChecker.verdict("5.6.7.8", acl))
    }
}
