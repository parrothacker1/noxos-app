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

`noxos-app` is the host-side Android app — branded **Warden** — that drives NoxOS's core security feature. It lets users pick untrusted files, routes them into a disposable Microdroid virtual machine for isolated processing, records every execution in a local audit database, and enforces a real per-IP blocklist on the network side.

The app is built as a **multi-module Gradle project** (Kotlin DSL, version catalog) with clean separation between the isolation trigger, the network monitor, and the shared data layer (audit trail, blocked hosts, settings, design system).

Visual design (dark-first, navy/teal, Archivo + JetBrains Mono, a dashed-circle-and-dot "containment mark" motif reused across screens) follows a redesign delivered 2026-08-16 — see `knowledge-graph/TASKS.md` for the session log and every place a mockup detail was deliberately simplified rather than faked with data the app doesn't actually have (no fabricated TLS/threat-intel detection, no invented file hashes or byte counters).

---

## Module Architecture

```
┌──────────────────────────────────────────────────────┐
│                        :app                          │
│  MainActivity · HomeScreen · SAF file/export pickers │
│  VPN + notification permission flows · navigation    │
└────────┬─────────────┬───────────────┬───────────────┘
         │             │               │
         ▼             ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌────────────────────────────────┐
│:trigger-     │ │ :netmonitor  │ │            :audit               │
│ router       │ │              │ │                                  │
│              │ │ VpnService   │ │ AuditEvent/BlockedHost domain   │
│ TriggerRouter│ │ capture loop │ │ Room DB / DAO (audit + hosts)   │
│ ScanProgress │ │ UDP relay    │ │ WardenSettingsRepository        │
│ VmSession    │ │ blocklist    │ │  (DataStore: theme, vm timeout, │
│ VsockTransport│ │ enforcement  │ │   alerts, retention)            │
│ Protocol     │ │ (via :audit) │ │ AuditExport (JSON via SAF)      │
│ ScanResult   │ │              │ │ theme/ — WardenTheme, Containment│
└──────┬───────┘ └──────┬───────┘ │  Mark, colors/type              │
       │                │         │ List/Detail/BlockedHosts/       │
       └────────────────┴────────▶│  Settings Compose screens       │
              depends on :audit    └──────────────────────────────────┘
```

### Module Responsibilities

| Module | Responsibility |
|--------|---------------|
| **`:app`** | Composition root, `MainActivity` (`ComponentActivity`), Compose nav across Home / Audit / Blocked Hosts / Settings, VPN + `POST_NOTIFICATIONS` permission launchers, SAF file picker, scan-completion notifications |
| **`:trigger-router`** | `TriggerRouter` — reads file bytes, spawns VM session, sends/receives vsock frames, parses JSON result, records audit event. Publishes live `ScanProgress` (boot/execute/sanitize/destroy, real per-step timings) as a `StateFlow`; scans are cancellable and the VM is always torn down (`use{}`) even on cancel. VM session timeout is read from `WardenSettingsRepository`, not hardcoded |
| **`:netmonitor`** | `NetMonitorService` — `VpnService` subclass, TUN capture loop, real UDP relay through a protected per-client socket, blocked-host enforcement (checks the live blocklist before relaying, logs `BLOCKED` instead of `SUCCESS` when it drops a packet), throttled audit logging. `NetMonitor` — controller managing start/stop, exposes a live connections-inspected counter |
| **`:audit`** | The shared leaf module — domain types (`AuditEvent`, `BlockedHost`), Room entities/DAOs/database (`audit_entries` + `blocked_hosts`), repositories (`RoomAuditRepository`, `RoomBlockedHostRepository`), `WardenSettingsRepository` (DataStore-backed: theme mode, VM timeout, alert toggles, retention days), `AuditExport` (JSON export helper), `RetentionPolicy` (pure cutoff-date math), list filtering/date-bucketing/severity helpers, the `theme/` package (`WardenTheme`, `ContainmentMark`, color/type tokens, bundled Archivo + JetBrains Mono fonts), and every Compose screen except Home (List, Detail, Blocked Hosts, Settings) |

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

> **AVF seam note:** `MicrodroidVmSession` and `VsockVmTransport` are written against documented-but-unconfirmed AVF Java APIs. Compiles clean (resolved 2026-08-15 via a `compileOnly` system-API stub jar, see `knowledge-graph/TASKS.md`), but the actual runtime behavior is still unverified on real hardware/Cuttlefish — that's Phase 2.
>
> **Cancellation and timeout are real.** The scan coroutine can be cancelled mid-flight (Home screen's "Cancel Scan"); VM teardown (`vm.stop()` via `VmSession.close()`) always runs because it happens inside `Closeable.use{}`'s `finally`, which isn't itself a suspending call and so isn't skipped by cancellation. The timeout itself is user-configurable (Settings → VM session timeout) via `WardenSettingsRepository`, not a hardcoded constant.

---

## Audit Trail

Every scan (and every network flow captured by the VPN monitor) is persisted to a local SQLite database via Room:

```
AuditEvent {
  id                  Long        (auto-generated)
  timestampEpochMillis Long
  eventType           FILE_SCAN | NETWORK_TRAFFIC
  inputDescriptor     String      (filename or "UDP 1.2.3.4:80 → 5.6.7.8:443")
  outcome             SUCCESS | FAILURE | ERROR | BLOCKED
  resultSummary       String?
  durationMillis      Long
  errorMessage        String?
  flagged             Boolean     (user-toggled, or auto-set for unrelayed network flows)
  remoteHost          String?     (network events only)
  stepTimingsCsv       String?     (file scans only — real per-step VM timings, "BOOTING:400,EXECUTING:1800,...")
}
```

Exposed as a `Flow<List<AuditEvent>>` so the Compose UI reacts to inserts in real-time. `flagged` is never set from a fabricated detection signal (no invented TLS-fingerprint/threat-intel logic) — it's either a manual user action or an honest "this flow wasn't actually relayed" marker (TCP isn't relayed yet, see `netmonitor`'s known-gap below).

### Blocked hosts

A second table (`blocked_hosts`: `host`, `reason`, `blockedAtEpochMillis`) backs real enforcement, not just a UI list. `NetMonitorService` subscribes to the live blocklist and checks the destination IP before relaying any UDP packet; a match is dropped and logged as `AuditOutcome.BLOCKED` instead of forwarded. Hosts get added either from the Blocked Hosts screen's manual entry or from a network event's detail screen ("Block Host").

---

## Build Config

| Property | Value |
|----------|-------|
| `minSdk` | `33` (Android 13 — AVF/Microdroid floor, and the floor where `POST_NOTIFICATIONS` became a runtime permission) |
| `compileSdk` / `targetSdk` | `35` (Android 15) |
| AGP | `8.7.3` |
| Kotlin | `2.0.21` |
| KSP | `2.0.21-1.0.28` |
| Compose BOM | `2024.10.01` (pinned — see the version catalog comment for why newer BOMs don't work under this AGP/compileSdk pin) |
| Room | `2.6.1` |
| DataStore (Preferences) | `1.1.1` — `WardenSettingsRepository` in `:audit` |
| Gradle Wrapper | `8.9` |
| CI | GitHub-hosted runners, `./gradlew build` (incl. Robolectric tests) |

> **Local SDK note:** This dev machine has no Android SDK. Local validation is structural only. Real build/test validation runs on CI.

---

<div align="center">
<sub>Part of <a href="https://github.com/parrothacker1/noxos">NoxOS</a></sub>
</div>
