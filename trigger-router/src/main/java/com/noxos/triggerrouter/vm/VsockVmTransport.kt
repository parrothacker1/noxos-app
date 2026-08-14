// UNVERIFIED: This class is written against documented-but-unconfirmed AVF Java APIs
// and cannot be verified until Phase 2 on real hardware/Cuttlefish.
package com.noxos.triggerrouter.vm

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream

class VsockVmTransport(private val pfd: ParcelFileDescriptor) : VmTransport {
    private val inputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    private val dataInput = DataInputStream(inputStream)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        outputStream.write(bytes)
        outputStream.flush()
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val length = dataInput.readInt()
        if (length < 0 || length > 10 * 1024 * 1024) {
            throw IllegalArgumentException("Invalid response length: $length")
        }
        val bytes = ByteArray(length)
        dataInput.readFully(bytes)
        bytes
    }
}
