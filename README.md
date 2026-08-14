<div align="center">

<img src="https://raw.githubusercontent.com/parrothacker1/noxos/main/assets/branding/logo-mark.svg" width="80" height="80" alt="NoxOS Logo">

# noxos-app

**Host-side Android application for NoxOS**

![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-33%20(Android%2013)-3DDC84?style=flat-square&logo=android&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.08-4285F4?style=flat-square)
![Room](https://img.shields.io/badge/Room-2.6.1-orange?style=flat-square)
![CI](https://img.shields.io/github/actions/workflow/status/parrothacker1/noxos-app/ci.yml?style=flat-square&label=CI)

</div>

---

## Overview

`noxos-app` is the host-side Android app — branded **Warden** — that drives NoxOS's core security feature. It lets users pick untrusted files, routes them into a disposable Microdroid virtual machine for isolated processing, and records every execution in a local audit database.

The app is built as a **multi-module Gradle project** (Kotlin DSL, version catalog) with clean separation between the isolation trigger, the network monitor, and the audit persistence layer.

---

## Module Architecture

```
┌──────────────────────────────────────────────┐
│                    :app                      │
│  MainActivity · HomeScreen · SAF file picker │
│  VPN permission flow · navigation            │
└────────┬─────────────┬──────────────┬────────┘
         │             │              │
         ▼             ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐
│:trigger-     │ │ :netmonitor  │ │       :audit         │
│ router       │ │              │ │                      │
│              │ │ VpnService   │ │ AuditEvent domain    │
│ TriggerRouter│ │ capture loop │ │ AuditRepository      │
│ VmSession    │ │ IPv4 flow    │ │ Room DB / DAO        │
│ VsockTransport│ │ descriptor   │ │ AuditListScreen      │
│ Protocol     │ │ logging      │ │ AuditDetailScreen    │
│ ScanResult   │ │              │ │                      │
└──────┬───────┘ └──────┬───────┘ └──────────────────────┘
       │                │
       └────────────────┘
              depends on :audit
```

### Module Responsibilities

| Module | Responsibility |
|--------|---------------|
| **`:app`** | Composition root, `MainActivity` (`ComponentActivity`), Compose nav between Home / Audit screens, VPN permission launcher, SAF file picker |
| **`:trigger-router`** | `TriggerRouter` — reads file bytes, spawns VM session, sends/receives vsock frames, parses JSON result, records audit event |
| **`:netmonitor`** | `NetMonitorService` — `VpnService` subclass, TUN capture loop, IPv4 flow descriptor extraction, audit logging. `NetMonitor` — controller managing start/stop |
| **`:audit`** | `AuditEvent`/`AuditRepository` domain types, Room entity/DAO/database, `RoomAuditRepository`, `AuditModule.create()` factory, Compose list + detail screens |

---

## Host ↔ Guest VM Protocol

All communication with the Microdroid guest uses **vsock on port 5000** with a simple length-prefixed framing protocol:

```
Host → Guest:
  ┌──────────────────┬──────────────────────┐
  │  4 bytes (BE)    │  N bytes             │
  │  Length = N      │  Raw file bytes      │
  └──────────────────┴──────────────────────┘

Guest → Host:
  ┌──────────────────┬────────┬─────────────────────────────┐
  │  4 bytes (BE)    │ 1 byte │  M bytes (UTF-8)            │
  │  Length = 1 + M  │ Status │  JSON result or error       │
  └──────────────────┴────────┴─────────────────────────────┘

Status codes:
  0x00  →  OK         {"Make":"Google","Model":"Pixel 9",...}
  0x01  →  PARSE_ERROR  {"error":"no EXIF APP1 segment found"}
  0x02  →  MALFORMED    {"error":"not a JPEG file"}
```

The protocol is implemented in `VmPayloadProtocol.kt` on the host side and mirrored byte-for-byte in `payload_main.cpp` on the guest.

---

## Scan Lifecycle

```
User picks file
     │
     ▼
TriggerRouter.scanFile(uri)
     │
     ├─ Read file bytes via ContentResolver
     │
     ├─ RealVmSessionFactory.createSession()
     │    └─ VirtualMachineManager.getOrCreate()   ← AVF API (unverified until Phase 2)
     │         vm.run()
     │
     ├─ VsockVmTransport.connect(port=5000)
     │
     ├─ send: [4-byte length][file bytes]
     │
     ├─ receive: [4-byte length][status][JSON]
     │
     ├─ parse JSON → ScanResult.Success / Failure / Error
     │
     ├─ vm.stop()   ← always, via Closeable.use{}
     │
     └─ AuditRepository.record(AuditEvent)
```

> **AVF seam note:** `MicrodroidVmSession` and `VsockVmTransport` are written against documented-but-unconfirmed AVF Java APIs. They are explicitly flagged in-code and will be verified in Phase 2 on real Cuttlefish hardware.
>
> **This is currently a compile-time blocker, not just a runtime-unverified one.** CI confirmed `VirtualMachine`/`VirtualMachineManager`/`VirtualMachineConfig` aren't present in the standard `compileSdk 35` public SDK jar — these are system/hidden APIs. Whether a compileOnly stub artifact exists, or this module needs to be built inside an AOSP tree instead of a plain Gradle project, is unresolved. See `knowledge-graph/TASKS.md` for the open question.

---

## Audit Trail

Every scan (and every network flow captured by the VPN monitor) is persisted to a local SQLite database via Room:

```
AuditEvent {
  id                  Long        (auto-generated)
  timestampEpochMillis Long
  eventType           FILE_SCAN | NETWORK_TRAFFIC
  inputDescriptor     String      (filename or "TCP 1.2.3.4:80 → 5.6.7.8:443")
  outcome             SUCCESS | FAILURE | ERROR
  resultSummary       String?
  durationMillis      Long
  errorMessage        String?
}
```

Exposed as a `Flow<List<AuditEvent>>` so the Compose UI reacts to inserts in real-time.

---

## Build Config

| Property | Value |
|----------|-------|
| `minSdk` | `33` (Android 13 — AVF/Microdroid floor) |
| `compileSdk` / `targetSdk` | `35` (Android 15) |
| AGP | `8.7.3` |
| Kotlin | `2.0.21` |
| KSP | `2.0.21-1.0.28` |
| Compose BOM | `2026.08.00` |
| Room | `2.6.1` |
| Gradle Wrapper | `8.9` |
| CI | GitHub-hosted runners, `./gradlew build` (incl. Robolectric tests) |

> **Local SDK note:** This dev machine has no Android SDK. Local validation is structural only. Real build/test validation runs on CI.

---

<div align="center">
<sub>Part of <a href="https://github.com/parrothacker1/noxos">NoxOS</a></sub>
</div>
