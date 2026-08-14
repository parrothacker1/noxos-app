# noxos-app

Host-side Android app for NoxOS, one multi-module Gradle project:

- Host Trigger/Router — detects untrusted files/payloads and routes them to the Microdroid pVM
- VpnService network monitor — per-app traffic visibility, no root needed
- Audit & Observability UI — surfaces isolated-execution and traffic logs

Builds entirely on free GitHub-hosted runners.

## Modules

Gradle multi-module project, Kotlin DSL, with a version catalog (`gradle/libs.versions.toml`):

- `app` — application module, launcher activity, depends on the other three modules
- `trigger-router` — Android library, will detect untrusted content and route it to the Microdroid VM
- `netmonitor` — Android library, will hold the `VpnService`-based per-app network monitor
- `audit` — Android library, will hold the audit/observability UI

`minSdk 33` (Android 13, the AVF/Microdroid floor), `compileSdk`/`targetSdk 35`.

All four modules are currently empty skeletons (manifest + one placeholder Kotlin object each) —
no trigger/routing/VPN/audit logic yet. That lands in later roadmap phases (P6-P10).

Note: this project was scaffolded without a local Android SDK, so only `./gradlew help` /
`./gradlew projects`-level structural validation was possible locally; the real build runs in CI
(`.github/workflows/ci.yml`) on GitHub-hosted runners.

## Icon

Adaptive launcher icon (`res/mipmap-anydpi-v26/`, vector drawables) + legacy PNG fallbacks for
API < 26. Source SVGs live in the root `noxos` repo under `assets/branding/`.

Part of [NoxOS](https://github.com/parrothacker1/noxos). Status: skeleton only.
