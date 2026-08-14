package com.noxos.triggerrouter

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.noxos.audit.AuditEvent
import com.noxos.audit.AuditOutcome
import com.noxos.audit.AuditRepository
import com.noxos.triggerrouter.vm.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TriggerRouterTest {

    private lateinit var context: Context
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var transport: FakeVmTransport
    private lateinit var sessionFactory: FakeVmSessionFactory
    private lateinit var router: TriggerRouter
    private val testUri = Uri.parse("content://test/file.jpg")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        auditRepository = FakeAuditRepository()
        transport = FakeVmTransport()
        sessionFactory = FakeVmSessionFactory(transport)
        router = TriggerRouter(context, auditRepository, sessionFactory)
    }

    @Test
    fun testScanFileSuccess() = runBlocking {
        val fileContent = "dummy_file_bytes"
        ShadowContentResolver.registerInputStream(
            testUri,
            ByteArrayInputStream(fileContent.toByteArray(Charsets.UTF_8))
        )

        val jsonResponse = JSONObject().put("make", "Google").put("model", "Pixel 9").toString()
        val jsonBytes = jsonResponse.toByteArray(Charsets.UTF_8)
        val responseBytes = ByteArray(1 + jsonBytes.size)
        responseBytes[0] = 0.toByte()
        System.arraycopy(jsonBytes, 0, responseBytes, 1, jsonBytes.size)
        transport.bytesToReceive = responseBytes

        val result = router.scanFile(testUri, "file.jpg")

        assertTrue(result is ScanResult.Success)
        val success = result as ScanResult.Success
        assertEquals("Google", success.exifData.metadata["make"])
        assertEquals("Pixel 9", success.exifData.metadata["model"])

        assertEquals(1, auditRepository.recordedEvents.size)
        val audit = auditRepository.recordedEvents[0]
        assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        assertEquals("file.jpg", audit.inputDescriptor)
        assertNull(audit.errorMessage)
        assertTrue(sessionFactory.lastSession?.isClosed == true)
    }

    @Test
    fun testScanFileFailure() = runBlocking {
        val fileContent = "dummy_file_bytes"
        ShadowContentResolver.registerInputStream(
            testUri,
            ByteArrayInputStream(fileContent.toByteArray(Charsets.UTF_8))
        )

        val errMessage = "Corrupt EXIF header"
        val errBytes = errMessage.toByteArray(Charsets.UTF_8)
        val responseBytes = ByteArray(1 + errBytes.size)
        responseBytes[0] = 1.toByte()
        System.arraycopy(errBytes, 0, responseBytes, 1, errBytes.size)
        transport.bytesToReceive = responseBytes

        val result = router.scanFile(testUri, "file.jpg")

        assertTrue(result is ScanResult.Failure)
        val failure = result as ScanResult.Failure
        assertTrue(failure.reason.contains("Corrupt EXIF header"))

        assertEquals(1, auditRepository.recordedEvents.size)
        val audit = auditRepository.recordedEvents[0]
        assertEquals(AuditOutcome.FAILURE, audit.outcome)
        assertEquals("Corrupt EXIF header", audit.errorMessage)
        assertTrue(sessionFactory.lastSession?.isClosed == true)
    }
}

class FakeAuditRepository : AuditRepository {
    val recordedEvents = mutableListOf<AuditEvent>()
    
    override suspend fun record(event: AuditEvent) {
        recordedEvents.add(event)
    }

    override fun observeAll(): Flow<List<AuditEvent>> {
        return MutableStateFlow(recordedEvents)
    }

    override suspend fun get(id: Long): AuditEvent? {
        return recordedEvents.find { it.id == id }
    }
}

class FakeVmSession(private val transport: VmTransport) : VmSession {
    var isClosed = false

    override suspend fun getTransport(): VmTransport = transport

    override fun close() {
        isClosed = true
    }
}

class FakeVmSessionFactory(private val transport: VmTransport) : VmSessionFactory {
    var lastSession: FakeVmSession? = null

    override fun createSession(context: Context): VmSession {
        val session = FakeVmSession(transport)
        lastSession = session
        return session
    }
}
