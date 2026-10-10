package com.noxos.triggerrouter.vm

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class VmInstanceDirs(private val root: File) {

    fun register(name: String) {
        active.add(name)
    }

    fun release(name: String): Boolean {
        active.remove(name)
        val dir = File(root, name)
        return !dir.exists() || dir.deleteRecursively()
    }

    fun purgeLeftovers(): Int {
        val leftovers = root.listFiles { file ->
            file.isDirectory && file.name.startsWith(NAME_PREFIX) && file.name !in active
        } ?: return 0
        return leftovers.count { it.deleteRecursively() }
    }

    companion object {
        const val NAME_PREFIX = "noxos-scan-"

        private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun of(context: Context) = VmInstanceDirs(File(context.dataDir, "vm"))
    }
}
