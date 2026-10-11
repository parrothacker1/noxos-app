package com.noxos.triggerrouter

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.noxos.audit.AuditOutcome
import com.noxos.audit.WardenSettingsRepository
import com.noxos.triggerrouter.vm.VmSession
import com.noxos.triggerrouter.vm.VmSessionFactory
import com.noxos.triggerrouter.vm.VmTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TriggerRouterQueueTest {

    private lateinit var context: Context
    private lateinit var auditRepository: FakeAuditRepository
    private lateinit var factory: CountingFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        auditRepository = FakeAuditRepository()
        factory = CountingFactory()
    }

    private class SlowTransport(private val holdMillis: Long, private val failOnReceive: Boolean = false) : VmTransport {
        override suspend fun send(bytes: ByteArray) {}
        override suspend fun sendStream(header: ByteArray, source: InputStream, length: Long) {
            source.readBytes()
        }

        override suspend fun receive(): ByteArray {
            delay(holdMillis)
            if (failOnReceive) throw IllegalStateException("guest vanished")
            return byteArrayOf(0) + "{\"file_type\":\"png\",\"width\":1}".toByteArray()
        }
    }

    private class CountingFactory(var holdMillis: Long = 250, var failOnReceive: Boolean = false) : VmSessionFactory {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val created = AtomicInteger(0)

        override fun createSession(context: Context): VmSession {
            created.incrementAndGet()
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            return object : VmSession {
                override suspend fun getTransport(): VmTransport = SlowTransport(holdMillis, failOnReceive)
                override fun close() {
                    active.decrementAndGet()
                }
            }
        }
    }

    private fun router(slots: Semaphore, queueWaitMillis: Long = 60_000) =
        TriggerRouter(context, auditRepository, factory, WardenSettingsRepository(context), slots, queueWaitMillis)

    private fun uri(n: Int): Uri =
        Uri.parse("content://queue-test/file$n.png").also {
            shadowOf(context.contentResolver).registerInputStream(it, ByteArrayInputStream(ByteArray(16) { b -> b.toByte() }))
        }

    @Test
    fun scansBeyondTheLimitWaitInTheQueueAndAllStillComplete() = runBlocking {
        val router = router(Semaphore(2))

        val results = (1..5).map { n -> async { router.scanFile(uri(n), "file$n.png") } }.awaitAll()

        assertEquals(5, results.count { it is ScanResult.Success })
        assertEquals(5, factory.created.get())
        assertTrue("never more than 2 VMs at once, saw ${factory.maxActive.get()}", factory.maxActive.get() <= 2)
        assertEquals(0, factory.active.get())
    }

    @Test
    fun aLimitOfOneRunsScansStrictlyOneAfterAnother() = runBlocking {
        val router = router(Semaphore(1))

        val results = (1..3).map { n -> async { router.scanFile(uri(n + 10), "file$n.png") } }.awaitAll()

        assertEquals(3, results.count { it is ScanResult.Success })
        assertEquals(1, factory.maxActive.get())
    }

    @Test
    fun aScanThatWaitsLongerThanTheQueueLimitFailsClosedWithoutBootingAVm() = runBlocking {
        val slots = Semaphore(1)
        slots.acquire()
        val router = router(slots, queueWaitMillis = 200)

        val result = router.scanFile(uri(20), "stuck.png")

        assertTrue(result is ScanResult.Error)
        assertTrue((result as ScanResult.Error).message.contains("queue"))
        assertEquals(0, factory.created.get())
        assertEquals(AuditOutcome.ERROR, auditRepository.recordedEvents.single().outcome)
        slots.release()
        val after = router.scanFile(uri(21), "after.png")
        assertTrue(after is ScanResult.Success)
    }

    @Test
    fun aFailedScanReleasesItsSlotSoTheNextScanIsNotStuck() = runBlocking {
        val slots = Semaphore(1)
        val router = router(slots, queueWaitMillis = 2_000)
        factory.failOnReceive = true

        val failed = router.scanFile(uri(30), "boom.png")
        factory.failOnReceive = false
        val next = router.scanFile(uri(31), "ok.png")

        assertTrue(failed is ScanResult.Error)
        assertTrue(next is ScanResult.Success)
        assertEquals(1, slots.availablePermits)
    }
}
