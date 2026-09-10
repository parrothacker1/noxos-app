package com.noxos.triggerrouter

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

class FileArrivalWatcher(
    private val context: Context,
    private val onFileArrived: (Uri) -> Unit
) {
    private var lastSeenAddedAtEpochSeconds: Long = System.currentTimeMillis() / 1000
    private var observer: ContentObserver? = null

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
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DATE_ADDED)
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
            while (cursor.moveToNext()) {
                lastSeenAddedAtEpochSeconds = maxOf(lastSeenAddedAtEpochSeconds, cursor.getLong(dateCol))
                val id = cursor.getLong(idCol)
                onFileArrived(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()))
            }
        }
    }
}
