package com.noxos.triggerrouter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.noxos.audit.QuarantineRepository
import java.io.File
import java.util.UUID

/**
 * Physically moves a file the EXIF parser flagged as malformed out of MediaStore.Downloads and
 * into app-private storage, so it's no longer accessible to the user (or any other app) until
 * force-allowed back. Only ever called for auto-scanned files (see FileArrivalWatcher) - a
 * manually SAF-picked file isn't ours to move.
 */
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
        context.contentResolver.delete(uri, null, null)
        quarantineRepository.add(displayName, mimeType, storedFileName, reason)
        return true
    }

    /** Force-allow: restores a held file back into Downloads and forgets it was ever quarantined. */
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
        resolver.openOutputStream(restoredUri)?.use { it.write(file.readBytes()) }
        resolver.update(restoredUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)

        file.delete()
        quarantineRepository.remove(id)
        return true
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
