package com.noxos.triggerrouter.vm

class FakeVmTransport : VmTransport {
    var sentBytes: ByteArray? = null
    var bytesToReceive: ByteArray = byteArrayOf()
    var shouldThrowOnSend = false
    var shouldThrowOnReceive = false

    override suspend fun send(bytes: ByteArray) {
        if (shouldThrowOnSend) {
            throw Exception("Fake send failed")
        }
        sentBytes = bytes
    }

    override suspend fun receive(): ByteArray {
        if (shouldThrowOnReceive) {
            throw Exception("Fake receive failed")
        }
        return bytesToReceive
    }
}
