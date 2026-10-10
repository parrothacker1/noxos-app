package com.noxos.triggerrouter

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScanNotificationsTest {

    @Test
    fun `an analyzed clean scan says sanitized`() {
        val result = ScanResult.Success(ExifData(mapOf("file_type" to "jpeg", "Make" to "Google")))

        assertEquals("Scan complete — sanitized", ScanNotifications.completionText(result, quarantined = false))
    }

    @Test
    fun `a clean scan that only identified the type does not claim to be sanitized`() {
        val result = ScanResult.Success(ExifData(mapOf("file_type" to "png")))

        assertEquals(
            "Scan complete — type not deeply inspected, not flagged",
            ScanNotifications.completionText(result, quarantined = false)
        )
    }

    @Test
    fun `failures and errors say quarantined only when they actually were`() {
        assertEquals("Quarantined: bad", ScanNotifications.completionText(ScanResult.Failure("bad"), quarantined = true))
        assertEquals("Scan flagged: bad", ScanNotifications.completionText(ScanResult.Failure("bad"), quarantined = false))
        assertEquals("Quarantined: boom", ScanNotifications.completionText(ScanResult.Error("boom"), quarantined = true))
        assertEquals("Scan error: boom", ScanNotifications.completionText(ScanResult.Error("boom"), quarantined = false))
    }
}
