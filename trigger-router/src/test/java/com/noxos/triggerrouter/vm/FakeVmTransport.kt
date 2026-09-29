package com.noxos.triggerrouter.vm

import java.io.InputStream

class FakeVmTransport : VmTransport {
    var sentBytes: ByteArray? = null
    val sentPayloads = mutableListOf<ByteArray>()
    var bytesToReceive: ByteArray = byteArrayOf()
    private val queuedResponses = ArrayDeque<ByteArray>()
    var shouldThrowOnSend = false
    var shouldThrowOnReceive = false

    fun queueResponse(bytes: ByteArray) {
        queuedResponses.addLast(bytes)
    }

    override suspend fun send(bytes: ByteArray) {
        if (shouldThrowOnSend) {
            throw Exception("Fake send failed")
        }
        sentBytes = bytes
        sentPayloads.add(bytes)
    }

    override suspend fun sendStream(header: ByteArray, source: InputStream, length: Long) {
        if (shouldThrowOnSend) {
            throw Exception("Fake send failed")
        }
        val body = header + source.readBytes()
        sentBytes = body
        sentPayloads.add(body)
    }

    override suspend fun receive(): ByteArray {
        if (shouldThrowOnReceive) {
            throw Exception("Fake receive failed")
        }
        return if (queuedResponses.isNotEmpty()) queuedResponses.removeFirst() else bytesToReceive
    }
}
