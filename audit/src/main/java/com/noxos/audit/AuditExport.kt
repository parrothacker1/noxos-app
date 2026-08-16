package com.noxos.audit

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object AuditExport {

    fun toJson(events: List<AuditEvent>): String {
        val array = JSONArray()
        events.forEach { array.put(toJsonObject(it)) }
        return array.toString(2)
    }

    fun toJson(event: AuditEvent): String = toJsonObject(event).toString(2)

    fun write(context: Context, uri: Uri, json: String) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    private fun toJsonObject(event: AuditEvent): JSONObject = JSONObject().apply {
        put("id", event.id)
        put("timestampEpochMillis", event.timestampEpochMillis)
        put("eventType", event.eventType.name)
        put("inputDescriptor", event.inputDescriptor)
        put("outcome", event.outcome.name)
        put("resultSummary", event.resultSummary)
        put("durationMillis", event.durationMillis)
        put("errorMessage", event.errorMessage)
        put("flagged", event.flagged)
        put("remoteHost", event.remoteHost)
    }
}
