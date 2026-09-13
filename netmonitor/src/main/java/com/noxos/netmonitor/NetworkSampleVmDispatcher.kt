package com.noxos.netmonitor

import android.content.Context
import android.util.Log
import com.noxos.audit.AclKind
import com.noxos.audit.AclRepository
import com.noxos.triggerrouter.protocol.VmPayloadProtocol
import com.noxos.triggerrouter.vm.VmSessionFactory
import kotlinx.coroutines.delay

internal sealed class CheapFilterVerdict {
    object Clean : CheapFilterVerdict()
    data class Flagged(val reason: String) : CheapFilterVerdict()
    object Unknown : CheapFilterVerdict()
}

class NetworkSampleVmDispatcher(
    private val context: Context,
    private val aclRepository: AclRepository,
    private val vmSessionFactory: VmSessionFactory
) {

    suspend fun run() {
        while (true) {
            aclRepository.nextCheapFilterBatch(BATCH_SIZE).forEach { entry ->
                val samples = NetMonitorService.pendingPacketSamples.remove(entry.subject)
                if (!samples.isNullOrEmpty()) {
                    when (val verdict = checkSample(context, vmSessionFactory, samples.toList())) {
                        CheapFilterVerdict.Clean ->
                            aclRepository.allow(AclKind.NETWORK, entry.subject, "cheap filter: clean", sessionOnly = true)
                        is CheapFilterVerdict.Flagged ->
                            aclRepository.markCheapFilterFlagged(AclKind.NETWORK, entry.subject, "cheap filter: ${verdict.reason}")
                        CheapFilterVerdict.Unknown -> {
                            Log.w(TAG, "no cheap-filter verdict for ${entry.subject}, sample consumed, will not retry this session")
                        }
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "WardenNetworkSampleVm"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val BATCH_SIZE = 5
        private val FLAGGED_FIELD = Regex(""""flagged"\s*:\s*(true|false)""")
        private val REASON_FIELD = Regex(""""reason"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        internal suspend fun checkSample(context: Context, vmSessionFactory: VmSessionFactory, samples: List<ByteArray>): CheapFilterVerdict {
            return try {
                vmSessionFactory.createSession(context).use { session ->
                    val transport = session.getTransport()
                    val payload = VmPayloadProtocol.encodePacketSamples(samples)
                    transport.send(VmPayloadProtocol.encodeRequest(VmPayloadProtocol.TASK_NETWORK_SAMPLE, payload))
                    val decoded = VmPayloadProtocol.decodeResponse(transport.receive())
                    if (decoded.status != 0) return@use CheapFilterVerdict.Unknown

                    val flagged = FLAGGED_FIELD.find(decoded.json)?.groupValues?.get(1) == "true"
                    if (flagged) {
                        val reason = REASON_FIELD.find(decoded.json)?.groupValues?.get(1)
                            ?: "flagged by in-VM packet sanity check"
                        CheapFilterVerdict.Flagged(reason)
                    } else {
                        CheapFilterVerdict.Clean
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "network sample VM check failed", e)
                CheapFilterVerdict.Unknown
            }
        }
    }
}
