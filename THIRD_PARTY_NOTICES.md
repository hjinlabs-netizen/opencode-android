# Third-Party Notices

This application bundles the following open-source libraries. Each remains the
property of its respective copyright holders under the license noted below.
Full license texts are available at the linked project pages; Apache-2.0
notices are preserved in the packaged `META-INF/` entries of the respective
artifacts.

## Runtime dependencies

| Library | Version | License | Copyright |
|---|---|---|---|
| [AndroidX Core (core-ktx)](https://developer.android.com/jetpack/androidx/releases/core) | 1.16.0 | Apache License 2.0 | The Android Open Source Project |
| [AndroidX Activity Compose](https://developer.android.com/jetpack/androidx/releases/activity) | 1.10.1 | Apache License 2.0 | The Android Open Source Project |
| [AndroidX Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) | 2.9.1 | Apache License 2.0 | The Android Open Source Project |
| [Jetpack Compose (BOM 2025.06.01: ui, ui-graphics, material3, material-icons-extended)](https://developer.android.com/jetpack/compose) | BOM 2025.06.01 | Apache License 2.0 | The Android Open Source Project |
| [AndroidX Navigation Compose](https://developer.android.com/jetpack/androidx/releases/navigation) | 2.9.0 | Apache License 2.0 | The Android Open Source Project |
| [AndroidX Hilt Navigation Compose](https://developer.android.com/jetpack/androidx/releases/hilt) | 1.2.0 | Apache License 2.0 | The Android Open Source Project |
| [AndroidX Security Crypto](https://developer.android.com/jetpack/androidx/releases/security) (EncryptedSharedPreferences) | 1.1.0-alpha06 | Apache License 2.0 | The Android Open Source Project |
| [Tink Java](https://github.com/tink-crypto/tink-java) (transitive via security-crypto) | — | Apache License 2.0 | The Tink Authors |
| [Hilt / Dagger](https://dagger.dev/hilt/) | 2.56.2 | Apache License 2.0 | The Dagger Authors |
| [Kotlin Standard Library](https://kotlinlang.org/) | 2.2.0 | Apache License 2.0 | JetBrains s.r.o. |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | Apache License 2.0 | JetBrains s.r.o. |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.9.0 | Apache License 2.0 | JetBrains s.r.o. |
| [Ktor client (core, okhttp, content-negotiation, kotlinx-json, logging)](https://ktor.io/) | 3.1.3 | Apache License 2.0 | JetBrains s.r.o. |
| [OkHttp](https://square.github.io/okhttp/) (engine behind ktor-client-okhttp) | transitive | Apache License 2.0 | Square, Inc. |
| [Okio](https://square.github.io/okio/) (transitive) | — | Apache License 2.0 | Square, Inc. |
| [SLF4J](https://www.slf4j.org/) (transitive via ktor-client-logging) | — | MIT License | QOS.ch |

## Test-only dependencies (not shipped)

| Library | Version | License | Copyright |
|---|---|---|---|
| [JUnit 4](https://junit.org/junit4/) | 4.13.2 | Eclipse Public License 1.0 | JUnit |
| [kotlinx-coroutines-test](https://github.com/Kotlin/kotlinx.coroutines) | 1.10.2 | Apache License 2.0 | JetBrains s.r.o. |
| [Turbine](https://github.com/cashapp/turbine) | 1.2.1 | Apache License 2.0 | Cash App / Block, Inc. |
| [ktor-client-mock](https://ktor.io/) | 3.1.3 | Apache License 2.0 | JetBrains s.r.o. |

## Upstream protocol reference

[OpenCode](https://github.com/anomalyco/opencode) — the open source AI coding
agent by [anomalyco](https://github.com/anomalyco) — is licensed under the
MIT License (Copyright (c) the OpenCode contributors). This client is an
independent implementation of OpenCode's documented server HTTP/SSE API: no
OpenCode source code, binaries, logos or other assets are bundled or derived.
"OpenCode" is a trademark of its respective owners and this project is not
affiliated with, endorsed by, or sponsored by the OpenCode team.
