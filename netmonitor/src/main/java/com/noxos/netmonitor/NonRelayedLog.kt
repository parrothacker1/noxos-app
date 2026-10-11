package com.noxos.netmonitor

import java.util.concurrent.ConcurrentHashMap

internal class NonRelayedLog {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun firstTime(protocol: Int, destination: String): Boolean = seen.add("$protocol:$destination")

    fun clear() = seen.clear()
}
