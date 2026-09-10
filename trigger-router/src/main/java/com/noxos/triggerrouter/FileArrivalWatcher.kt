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

class FileArrivalWatcher(
    private val context: Context,
    private val aclRepository: AclRepository,
    private val onFileArrived: (Uri) -> Unit
) {
    private var lastSeenAddedAtEpochSeconds: Long = System.currentTimeMillis() / 1000
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
            MediaStore.Downloads.OWNER_PACKAGE_NAME
        )
        val selection = "${MediaStore.Downloads.DATE_ADDED} > ?"
        val args = arrayOf(lastSeenAddedAtEpochSeconds.toString())

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
            while (cursor.moveToNext()) {
                lastSeenAddedAtEpochSeconds = maxOf(lastSeenAddedAtEpochSeconds, cursor.getLong(dateCol))
                val id = cursor.getLong(idCol)
                val owner = if (ownerCol >= 0) cursor.getString(ownerCol) else null
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                scope.launch {
                    if (!isTrustedSource(owner)) onFileArrived(uri)
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
