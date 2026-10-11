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
import com.noxos.audit.WardenSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class NewFileTracker(initialSeenEpochSeconds: Long) {
    var lastSeenEpochSeconds: Long = initialSeenEpochSeconds
        private set
    private val handledAtLastSecond = HashSet<Long>()

    fun accept(dateAddedEpochSeconds: Long, id: Long): Boolean {
        if (dateAddedEpochSeconds < lastSeenEpochSeconds) return false
        if (dateAddedEpochSeconds > lastSeenEpochSeconds) {
            lastSeenEpochSeconds = dateAddedEpochSeconds
            handledAtLastSecond.clear()
        }
        return handledAtLastSecond.add(id)
    }
}

internal data class ArrivedFile(
    val id: Long,
    val dateAddedEpochSeconds: Long,
    val owner: String?,
    val displayName: String
)

internal object CatchUp {
    const val MAX_FILES = 20
    const val MAX_AGE_SECONDS = 24 * 60 * 60L

    fun initialSeenSeconds(persistedSeconds: Long, nowSeconds: Long): Long =
        if (persistedSeconds <= 0L) nowSeconds else maxOf(persistedSeconds + 1, nowSeconds - MAX_AGE_SECONDS)

    fun newest(files: List<ArrivedFile>, limit: Int = MAX_FILES): List<ArrivedFile> =
        if (files.size <= limit) files else files.sortedBy { it.dateAddedEpochSeconds }.takeLast(limit)
}

class FileArrivalWatcher(
    private val context: Context,
    private val aclRepository: AclRepository,
    private val settingsRepository: WardenSettingsRepository,
    private val onFileArrived: (Uri, String) -> Unit
) {
    private var tracker = NewFileTracker(System.currentTimeMillis() / 1000)
    private var observer: ContentObserver? = null
    private var startJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Synchronized
    fun start() {
        if (startJob != null) return
        val resolver = context.contentResolver
        startJob = scope.launch {
            val now = System.currentTimeMillis() / 1000
            val persisted = settingsRepository.lastFileSeenEpochSeconds.first()
            synchronized(this@FileArrivalWatcher) {
                tracker = NewFileTracker(CatchUp.initialSeenSeconds(persisted, now))
                val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        pollNewFiles(resolver, null)
                    }
                }
                observer = obs
                resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, obs)
            }
            if (persisted <= 0L) settingsRepository.setLastFileSeenEpochSeconds(now)
            pollNewFiles(resolver, CatchUp.MAX_FILES)
        }
    }

    @Synchronized
    fun stop() {
        startJob?.cancel()
        startJob = null
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        val now = System.currentTimeMillis() / 1000
        scope.launch { settingsRepository.setLastFileSeenEpochSeconds(now) }
    }

    @Synchronized
    private fun pollNewFiles(resolver: ContentResolver, catchUpLimit: Int?) {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.OWNER_PACKAGE_NAME,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val selection = "${MediaStore.Downloads.DATE_ADDED} >= ?"
        val args = arrayOf(tracker.lastSeenEpochSeconds.toString())

        val accepted = ArrayList<ArrivedFile>()
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
                val dateAdded = cursor.getLong(dateCol)
                if (!tracker.accept(dateAdded, id)) continue
                accepted.add(
                    ArrivedFile(
                        id,
                        dateAdded,
                        if (ownerCol >= 0) cursor.getString(ownerCol) else null,
                        (if (nameCol >= 0) cursor.getString(nameCol) else null) ?: "unknown_file"
                    )
                )
            }
        }
        if (accepted.isEmpty()) return

        val seenSeconds = tracker.lastSeenEpochSeconds
        scope.launch { settingsRepository.setLastFileSeenEpochSeconds(seenSeconds) }

        val toScan = if (catchUpLimit != null) CatchUp.newest(accepted, catchUpLimit) else accepted
        toScan.forEach { file ->
            val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, file.id.toString())
            if (QuarantineManager.recentlyRestoredIds.remove(file.id)) return@forEach
            scope.launch {
                if (!isTrustedSource(file.owner)) onFileArrived(uri, file.displayName)
            }
        }
    }

    private suspend fun isTrustedSource(owner: String?): Boolean {
        if (owner == null) return false
        val entry = aclRepository.observeKind(AclKind.FILE_SOURCE).first().find { it.subject == owner }
        return entry?.state == AclState.ALLOWED
    }
}
