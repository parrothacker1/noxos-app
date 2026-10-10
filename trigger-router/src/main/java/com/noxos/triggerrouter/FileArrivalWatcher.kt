package com.noxos.triggerrouter

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.noxos.audit.AclKind
import com.noxos.audit.AclRepository
import com.noxos.audit.AclState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decides which MediaStore rows are new. DATE_ADDED has one-second granularity, so "newer than the last
 * second seen" silently drops a file added in the same second as one already handled (a batch download or
 * an extraction); instead a row is accepted once per (second, id).
 */
internal class NewFileTracker(initialSeenEpochSeconds: Long) {
    var lastSeenEpochSeconds: Long = initialSeenEpochSeconds
        private set
    private val handledAtLastSecond = HashSet<Long>()

    /** Feed rows in DATE_ADDED order. Returns true exactly once for each new (second, id). */
    fun accept(dateAddedEpochSeconds: Long, id: Long): Boolean {
        if (dateAddedEpochSeconds < lastSeenEpochSeconds) return false
        if (dateAddedEpochSeconds > lastSeenEpochSeconds) {
            lastSeenEpochSeconds = dateAddedEpochSeconds
            handledAtLastSecond.clear()
        }
        return handledAtLastSecond.add(id)
    }
}

class FileArrivalWatcher(
    private val context: Context,
    private val aclRepository: AclRepository,
    private val onFileArrived: (Uri, String) -> Unit
) {
    private val tracker = NewFileTracker(System.currentTimeMillis() / 1000)
    private var observer: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (observer != null) return
        val resolver = context.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                pollNewFiles(resolver)
            }
        }
        observer = obs
        resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, obs)
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun pollNewFiles(resolver: ContentResolver) {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.OWNER_PACKAGE_NAME,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val selection = "${MediaStore.Downloads.DATE_ADDED} >= ?"
        val args = arrayOf(tracker.lastSeenEpochSeconds.toString())

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Downloads.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            val ownerCol = cursor.getColumnIndex(MediaStore.Downloads.OWNER_PACKAGE_NAME)
            val nameCol = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                if (!tracker.accept(cursor.getLong(dateCol), id)) continue
                val owner = if (ownerCol >= 0) cursor.getString(ownerCol) else null
                val displayName = (if (nameCol >= 0) cursor.getString(nameCol) else null) ?: "unknown_file"
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                if (QuarantineManager.recentlyRestoredIds.remove(id)) continue
                scope.launch {
                    if (!isTrustedSource(owner)) onFileArrived(uri, displayName)
                }
            }
        }
    }

    private suspend fun isTrustedSource(owner: String?): Boolean {
        if (owner == null) return false
        val entry = aclRepository.observeKind(AclKind.FILE_SOURCE).first().find { it.subject == owner }
        return entry?.state == AclState.ALLOWED
    }
}
