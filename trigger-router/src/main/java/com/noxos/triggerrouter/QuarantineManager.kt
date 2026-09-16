package com.noxos.triggerrouter

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.noxos.audit.QuarantineRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class QuarantineManager(
    private val context: Context,
    private val quarantineRepository: QuarantineRepository
) {
    private val quarantineDir: File
        get() = File(context.filesDir, "quarantine").apply { mkdirs() }

    suspend fun quarantine(uri: Uri, displayName: String, mimeType: String?, reason: String): Boolean {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val storedFileName = "${UUID.randomUUID()}.bin"
        File(quarantineDir, storedFileName).writeBytes(bytes)
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "quarantined $displayName but could not delete the original at $uri", e)
        }
        quarantineRepository.add(displayName, mimeType, storedFileName, reason)
        return true
    }

    suspend fun restore(id: Long): Boolean {
        val entry = quarantineRepository.get(id) ?: return false
        val file = File(quarantineDir, entry.storedFileName)
        if (!file.exists()) {
            quarantineRepository.remove(id)
            return false
        }

        val resolver = context.contentResolver
        val insertValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, entry.originalDisplayName)
            entry.mimeType?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val restoredUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, insertValues) ?: return false
        recentlyRestoredIds.add(ContentUris.parseId(restoredUri))
        resolver.openOutputStream(restoredUri)?.use { it.write(file.readBytes()) }
        resolver.update(restoredUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)

        file.delete()
        quarantineRepository.remove(id)
        return true
    }

    companion object {
        private const val TAG = "WardenQuarantineManager"
        val recentlyRestoredIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    }

    suspend fun deletePermanently(id: Long) {
        val entry = quarantineRepository.get(id) ?: return
        File(quarantineDir, entry.storedFileName).delete()
        quarantineRepository.remove(id)
    }

    suspend fun purgeOlderThan(cutoffEpochMillis: Long): Int {
        val stale = quarantineRepository.entriesOlderThan(cutoffEpochMillis)
        stale.forEach { entry ->
            File(quarantineDir, entry.storedFileName).delete()
            quarantineRepository.remove(entry.id)
        }
        return stale.size
    }
}
