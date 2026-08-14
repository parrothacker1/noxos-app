package com.noxos.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.noxos.audit.AuditLog
import com.noxos.netmonitor.NetMonitor
import com.noxos.triggerrouter.TriggerRouter

/**
 * Launcher activity for the NoxOS host app.
 *
 * This is a project skeleton only: it wires up the trigger-router, netmonitor,
 * and audit modules as dependencies but implements no real behavior yet. The
 * actual untrusted-content detection/routing into a Microdroid VM, per-app
 * network monitoring, and audit trail UI land in later roadmap phases
 * (P6-P10).
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Placeholder wiring only, to prove the modules are linked; no real logic yet.
        val modules = listOf(TriggerRouter, NetMonitor, AuditLog)

        setContentView(
            TextView(this).apply {
                text = "NoxOS skeleton — ${modules.size} modules wired"
            }
        )
    }
}
