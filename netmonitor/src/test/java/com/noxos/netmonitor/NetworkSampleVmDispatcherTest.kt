package com.noxos.netmonitor

import android.content.Context
import android.content.ContextWrapper
import com.noxos.triggerrouter.protocol.VmPayloadProtocol
import com.noxos.triggerrouter.vm.VmSession
import com.noxos.triggerrouter.vm.VmSessionFactory
import com.noxos.triggerrouter.vm.VmTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSampleVmDispatcherTest {

    private fun response(status: Byte, json: String): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(1 + jsonBytes.size)
        bytes[0] = status
        System.arraycopy(jsonBytes, 0, bytes, 1, jsonBytes.size)
        return bytes
    }

    private val fakeContext: Context = ContextWrapper(null)

    @Test
    fun `a clean verdict is reported as Clean`() = runBlocking {
        val transport = FakeVmTransport(response(0, """{"flagged":false}"""))
        val verdict = NetworkSampleVmDispatcher.checkSample(fakeContext, FakeVmSessionFactory(transport), listOf(byteArrayOf(1, 2, 3)))

        assertEquals(CheapFilterVerdict.Clean, verdict)
    }

    @Test
    fun `a flagged verdict carries the real reason text`() = runBlocking {
        val transport = FakeVmTransport(response(0, """{"flagged":true,"reason":"TTL inconsistent with declared protocol"}"""))
        val verdict = NetworkSampleVmDispatcher.checkSample(fakeContext, FakeVmSessionFactory(transport), listOf(byteArrayOf(1, 2, 3)))

        assertTrue(verdict is CheapFilterVerdict.Flagged)
        assertEquals("TTL inconsistent with declared protocol", (verdict as CheapFilterVerdict.Flagged).reason)
    }

    @Test
    fun `a non-zero protocol status is Unknown, not a crash`() = runBlocking {
        val transport = FakeVmTransport(response(2, "malformed sample"))
        val verdict = NetworkSampleVmDispatcher.checkSample(fakeContext, FakeVmSessionFactory(transport), listOf(byteArrayOf(1, 2, 3)))

        assertEquals(CheapFilterVerdict.Unknown, verdict)
    }

    @Test
    fun `a VM session that throws resolves to Unknown instead of propagating`() = runBlocking {
        val verdict = NetworkSampleVmDispatcher.checkSample(fakeContext, ThrowingVmSessionFactory, listOf(byteArrayOf(1, 2, 3)))

        assertEquals(CheapFilterVerdict.Unknown, verdict)
    }

    @Test
    fun `the VM session is always closed`() = runBlocking {
        val transport = FakeVmTransport(response(0, """{"flagged":false}"""))
        val factory = FakeVmSessionFactory(transport)

        NetworkSampleVmDispatcher.checkSample(fakeContext, factory, listOf(byteArrayOf(1, 2, 3)))

        assertTrue(factory.lastSession?.isClosed == true)
    }

    @Test
    fun `both the outbound and inbound samples are sent in one framed request`() = runBlocking {
        val transport = FakeVmTransport(response(0, """{"flagged":false}"""))
        val outbound = byteArrayOf(1, 2, 3)
        val inbound = byteArrayOf(9, 8)

        NetworkSampleVmDispatcher.checkSample(fakeContext, FakeVmSessionFactory(transport), listOf(outbound, inbound))

        val expected = VmPayloadProtocol.encodeRequest(
            VmPayloadProtocol.TASK_NETWORK_SAMPLE,
            VmPayloadProtocol.encodePacketSamples(listOf(outbound, inbound))
        )
        assertArrayEquals(expected, transport.lastSent)
    }
}

private class FakeVmTransport(private val responseBytes: ByteArray) : VmTransport {
    var lastSent: ByteArray? = null

    override suspend fun send(bytes: ByteArray) {
        lastSent = bytes
    }

    override suspend fun receive(): ByteArray = responseBytes
}

private class FakeVmSession(private val transport: VmTransport) : VmSession {
    var isClosed = false

    override suspend fun getTransport(): VmTransport = transport

    override fun close() {
        isClosed = true
    }
}

private class FakeVmSessionFactory(private val transport: VmTransport) : VmSessionFactory {
    var lastSession: FakeVmSession? = null

    override fun createSession(context: Context): VmSession {
        val session = FakeVmSession(transport)
        lastSession = session
        return session
    }
}

private object ThrowingVmSessionFactory : VmSessionFactory {
    override fun createSession(context: Context): VmSession {
        throw IllegalStateException("VirtualMachineManager not supported on this device")
    }
}
