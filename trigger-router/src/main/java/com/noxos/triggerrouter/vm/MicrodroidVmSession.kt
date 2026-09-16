package com.noxos.triggerrouter.vm

import android.content.Context
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineManager
import com.noxos.triggerrouter.protocol.VmPayloadProtocol
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

class MicrodroidVmSession(
    private val context: Context,
    private val vm: VirtualMachine,
    private val payloadReady: CompletableDeferred<Unit>
) : VmSession {

    private var transport: VsockVmTransport? = null

    override suspend fun getTransport(): VmTransport {
        payloadReady.await()
        if (transport == null) {
            val pfd = vm.connectVsock(VmPayloadProtocol.VSOCK_PORT)
            transport = VsockVmTransport(pfd)
        }
        return transport!!
    }

    override fun close() {
        try {
            vm.stop()
        } catch (e: Exception) {
        }
    }
}

class MicrodroidVmSessionFactory : VmSessionFactory {
    override fun createSession(context: Context): VmSession {
        val vmm = context.getSystemService(VirtualMachineManager::class.java)
            ?: throw IllegalStateException("VirtualMachineManager not supported on this device")

        val config = VirtualMachineConfig.Builder(context)
            .setPayloadBinaryName("libnoxos_payload_stub.so")
            .setProtectedVm(false)
            .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_FULL)
            .build()

        val vmName = "noxos-scan-${UUID.randomUUID()}"
        val vm = vmm.getOrCreate(vmName, config)

        val payloadReady = CompletableDeferred<Unit>()
        vm.setCallback(
            context.mainExecutor,
            object : VirtualMachineCallback {
                override fun onPayloadStarted(vm: VirtualMachine) {}

                override fun onPayloadReady(vm: VirtualMachine) {
                    payloadReady.complete(Unit)
                }

                override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {
                    payloadReady.completeExceptionally(
                        IllegalStateException("VM payload finished before becoming ready (exit=$exitCode)")
                    )
                }

                override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
                    payloadReady.completeExceptionally(
                        IllegalStateException("VM error $errorCode: $message")
                    )
                }

                override fun onStopped(vm: VirtualMachine, reason: Int) {
                    payloadReady.completeExceptionally(
                        IllegalStateException("VM stopped before becoming ready (reason=$reason)")
                    )
                }
            }
        )

        vm.run()

        return MicrodroidVmSession(context, vm, payloadReady)
    }
}
