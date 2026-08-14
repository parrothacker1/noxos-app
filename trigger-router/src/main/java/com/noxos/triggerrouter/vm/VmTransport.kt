package com.noxos.triggerrouter.vm

interface VmTransport {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
}
