// UNVERIFIED: This class is written against documented-but-unconfirmed AVF Java APIs
// and cannot be verified until Phase 2 on real hardware/Cuttlefish.
package com.noxos.triggerrouter.vm

import android.content.Context
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineManager
import com.noxos.triggerrouter.protocol.VmPayloadProtocol
import java.util.UUID

class MicrodroidVmSession(
    private val context: Context,
    private val vm: VirtualMachine
) : VmSession {

    private var transport: VsockVmTransport? = null

    override suspend fun getTransport(): VmTransport {
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
            // Best effort
        }
    }
}

class RealVmSessionFactory : VmSessionFactory {
    override fun createSession(context: Context): VmSession {
        val vmm = context.getSystemService(VirtualMachineManager::class.java)
            ?: throw IllegalStateException("VirtualMachineManager not supported on this device")
        
        val config = VirtualMachineConfig.Builder(context, "vm_config.json")
            .setProtected(false)
            .build()
        
        val vmName = "noxos-scan-${UUID.randomUUID()}"
        val vm = vmm.getOrCreate(vmName, config)
        vm.run()
        
        return MicrodroidVmSession(context, vm)
    }
}
