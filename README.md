# Unofficial OpenCode Client for Android

Native Android client for [OpenCode](https://github.com/anomalyco/opencode), the open-source AI coding agent.
Built with Jetpack Compose, Ktor, and Hilt. Connects to a self-hosted OpenCode server
(PC, LAN, or cloud) over its HTTP API — the same model the official desktop and web apps use.

> **Note:** This project is not built by, affiliated with, or endorsed by the OpenCode team.
> It is an independent client that talks to a user-run OpenCode server.

> **Trademark:** "OpenCode" is a trademark of its respective owners. The name is used here
> descriptively, to identify the server software this independent client connects to. No
> OpenCode source code, binaries, logos or brand assets are included in this repository.
> See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Phase 1 — What's included

- **Project setup** — Kotlin DSL, version catalog (`gradle/libs.versions.toml`), AGP 8.11, Kotlin 2.2
- **Stack** — Compose Material 3, Navigation Compose, Coroutines/Flow, Kotlinx Serialization, Ktor (OkHttp engine), Hilt + KSP, security-crypto
- **Clean architecture layers**
  - `domain/` — immutable models (`ServerConfig`, `ConnectionState`, `HealthInfo`) + repository interface
  - `data/` — Ktor `HttpClient` wiring, `OpenCodeApi` (health probe via `GET /global/health`), `SecureSettingsStore` (EncryptedSharedPreferences + Keystore), `ConnectionStateManager`, repository impl
  - `di/` — Hilt modules (`NetworkModule`, `RepositoryModule`)
  - `ui/` — Material 3 theme (dark-first, OpenCode violet/cyan palette), navigation graph, Connection screen
- **Connection screen** — server URL + token input, live health check, secure persistence,
  color-coded status card for every connection state

## Build

Requirements: JDK 17+, Android SDK (API 36).

```bash
# Debug APK
./gradlew assembleDebug

# Release (needs signing config)
./gradlew assembleRelease
```

Or open the project in **Android Studio** (Narwhig or newer) and press Run.

## Run an OpenCode server to connect to

```bash
# Install the server (on your PC or your Oracle Cloud box)
curl -fsSL https://opencode.ai/install | bash

# Start it for LAN access with a password
opencode serve --hostname 0.0.0.0 --port 4096 --password mysecret
```

Then enter `http://<server-ip>:4096` and the password in the app.

> The Android manifest blocks cleartext HTTP. For LAN testing over plain HTTP,
> temporarily set `android:usesCleartextTraffic="true"` in the debug manifest
> (or add a network security config allowing your server's IP).

## Roadmap (planned)

- **Phase 2** — Session list + chat screen (streaming text/reasoning/tool parts) from `/session*` endpoints; WebSocket event stream with auto-reconnect
- **Phase 3** — Permission & question dialogs, diff viewer
- **Phase 4** — Provider/model management, settings, MCP support
- **Phase 5** — Generated client SDK from `openapi.json`, releases, polish

## Project layout

```
app/src/main/java/com/anomalyco/opencode/
├── OpenCodeApplication.kt      # @HiltAndroidApp
├── MainActivity.kt             # Single activity, edge-to-edge Compose
├── data/
│   ├── connection/ConnectionStateManager.kt   # State machine: Disconnected/Connecting/Connected/Error
│   ├── remote/OpenCodeApi.kt                 # Typed API calls (Ktor)
│   ├── repository/ConnectionRepositoryImpl.kt
│   └── settings/SecureSettingsStore.kt       # EncryptedSharedPreferences
├── di/
│   ├── NetworkModule.kt                      # HttpClient, JSON, timeouts
│   └── RepositoryModule.kt                   # Interface bindings
├── domain/
│   ├── model/                                # Immutable models
│   └── repository/ConnectionRepository.kt   # Contract
└── ui/
    ├── connection/                           # Dashboard/Connection screen + VM
    ├── navigation/OpenCodeNavGraph.kt
    └── theme/                               # Material 3 theme, dark/light
```
