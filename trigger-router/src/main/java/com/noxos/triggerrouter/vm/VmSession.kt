package com.noxos.triggerrouter.vm

import android.content.Context
import java.io.Closeable

interface VmSession : Closeable {
    suspend fun getTransport(): VmTransport
}

interface VmSessionFactory {
    fun createSession(context: Context): VmSession
}
