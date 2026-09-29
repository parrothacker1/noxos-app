package com.noxos.triggerrouter

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.noxos.audit.AuditOutcome
import com.noxos.audit.WardenSettingsRepository
import com.noxos.triggerrouter.vm.FakeVmTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// The /analyze/file result is ADVISORY ONLY. These tests pin the hard rules: it is fetched only for a clean
// APK with the "AI analysis of files" toggle on and an endpoint set, the request carries nothing but the
// permission list, and no server answer or failure can change the scan outcome or trigger quarantine.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TriggerRouterAdvisoryTest {

    private lateinit var context: Context
    private lateinit var settings: WardenSettingsRepository
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var transport: FakeVmTransport
    private lateinit var router: TriggerRouter
    private lateinit var file: File
    private lateinit var uri: Uri

    private val apkJson = JSONObject()
        .put("file_type", "apk")
        .put("zip_entries", 3)
        .put("permission_strings", 2)
        .put("permissions", org.json.JSONArray(listOf("android.permission.INTERNET", "android.permission.CAMERA")))
        .toString()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = WardenSettingsRepository(context)
        auditRepository = FakeAuditRepository()
        transport = FakeVmTransport()
        router = TriggerRouter(context, auditRepository, FakeVmSessionFactory(transport), settings)
        file = File.createTempFile("warden_advisory_test", ".apk").also { it.writeBytes("PK-not-really".toByteArray()) }
        uri = Uri.fromFile(file)
    }

    @After
    fun tearDown() {
        file.delete()
        runBlocking {
            settings.setInferenceEndpointUrl("")
            settings.setAiFileAnalysisEnabled(true)
        }
    }

    private fun answer(status: Int, json: String) {
        transport.bytesToReceive = byteArrayOf(status.toByte()) + json.toByteArray(Charsets.UTF_8)
    }

    private fun configure(url: String, aiFiles: Boolean = true) = runBlocking {
        settings.setInferenceEndpointUrl(url)
        settings.setInferenceApiKey("k")
        settings.setAiFileAnalysisEnabled(aiFiles)
    }

    @Test
    fun `a clean apk gets an advisory sent as the permission list only, and even a block verdict changes nothing`() {
        FakeInferenceServer(responseBody = FakeInferenceServer.ADVISORY_BLOCK).use { server ->
            configure(server.url)
            answer(0, apkJson)

            val result = runBlocking { router.scanFile(uri, "app.apk") }

            assertTrue(result is ScanResult.Success)
            val success = result as ScanResult.Success
            assertNotNull(success.advisory)
            assertEquals("block", success.advisory!!.verdict)
            assertEquals(
                "{\"permission_strings\":[\"android.permission.INTERNET\",\"android.permission.CAMERA\"]}",
                server.requests.single().body
            )
            assertFalse(success.exifData.metadata.containsKey("permissions"))
            val audit = auditRepository.recordedEvents.single()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertNull(audit.errorMessage)
            assertTrue(audit.resultSummary!!.contains("Advisory: advisory only (permission-based model"))
        }
    }

    @Test
    fun `nothing is sent when the AI analysis of files toggle is off`() {
        FakeInferenceServer().use { server ->
            configure(server.url, aiFiles = false)
            answer(0, apkJson)

            val result = runBlocking { router.scanFile(uri, "app.apk") } as ScanResult.Success

            assertNull(result.advisory)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `nothing is sent and nothing breaks when no endpoint is configured`() {
        answer(0, apkJson)

        val result = runBlocking { router.scanFile(uri, "app.apk") } as ScanResult.Success

        assertNull(result.advisory)
        assertEquals(AuditOutcome.SUCCESS, auditRepository.recordedEvents.single().outcome)
    }

    @Test
    fun `only apks are sent, never other file types even if a permissions key appears`() {
        FakeInferenceServer().use { server ->
            configure(server.url)
            answer(0, JSONObject(apkJson).put("file_type", "zip").toString())

            val result = runBlocking { router.scanFile(uri, "x.zip") } as ScanResult.Success

            assertNull(result.advisory)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `an apk with no permission list sends nothing`() {
        FakeInferenceServer().use { server ->
            configure(server.url)
            answer(0, "{\"file_type\":\"apk\",\"zip_entries\":3,\"permission_strings\":0}")

            val result = runBlocking { router.scanFile(uri, "app.apk") } as ScanResult.Success

            assertNull(result.advisory)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `a flagged apk is still quarantined-eligible and nothing is sent`() {
        FakeInferenceServer().use { server ->
            configure(server.url)
            answer(0, JSONObject(apkJson).put("cheap_filter_flagged", true).put("cheap_filter_reason", "bad").toString())

            val result = runBlocking { router.scanFile(uri, "app.apk") }

            assertTrue(result is ScanResult.Failure)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `a server outage never turns a clean scan into a failure`() {
        FakeInferenceServer(status = 503, responseBody = "{}").use { server ->
            configure(server.url)
            answer(0, apkJson)

            val result = runBlocking { router.scanFile(uri, "app.apk") }

            assertTrue(result is ScanResult.Success)
            assertNull((result as ScanResult.Success).advisory)
            assertEquals(AuditOutcome.SUCCESS, auditRepository.recordedEvents.single().outcome)
        }
    }

    @Test
    fun `a response not marked advisory is not shown`() {
        FakeInferenceServer(responseBody = "{\"verdict\":\"allow\",\"safety_score\":0.9,\"reasoning\":\"fine\"}").use { server ->
            configure(server.url)
            answer(0, apkJson)

            val result = runBlocking { router.scanFile(uri, "app.apk") } as ScanResult.Success

            assertNull(result.advisory)
            assertFalse(auditRepository.recordedEvents.single().resultSummary!!.contains("Advisory"))
        }
    }
}
