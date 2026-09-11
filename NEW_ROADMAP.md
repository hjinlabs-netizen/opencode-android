# OpenCode Android — Master Roadmap & Full-Project Audit

> Produced after Phase 5 (workspace directory selection, session deletion, file-to-chat context).
> Snapshot: 75 main sources / 20 test classes, **160/160 unit tests**, debug + R8-minified
> release assemble cleanly (release ≈ 1.7 MB). Stack: Kotlin 2.2, AGP 8.11, Compose BOM 2025.06,
> Hilt 2.56, Ktor 3.1.3 (OkHttp + SSE), kotlinx.serialization 1.9, coroutines 1.10.

---

## 0. Current State Summary

| Layer | Status |
|---|---|
| Networking | Ktor single client, lenient shared `Json` (`explicitNulls=false`), `requireActiveServer()` resolution, friendly error mapping |
| Streaming | SSE `/event`, handshake→`Connected`, capped exponential backoff + jitter, healthy-reset ladder, replay=0 SharedFlow |
| Sessions | list / create (quick + custom dir + recents) / delete (multi-select, batch, optimistic cache) |
| Chat | history + live assembler (merge by partID/callID/stepID), optimistic send, end-of-turn long-poll reconciliation, permission/question dialogs, model picker + search |
| Files | `/fs/list → /find → /file/find → /file` candidate probing, tolerant decoding, unified-diff parser + color viewer, non-git 400 degradation, `@path` context insertion via nav result |
| Settings | server info (masked token), disconnect/erase, theme mode (System/Dark/Light), latency + version + active-model diagnostics |
| Release | R8 rules verified via `assembleRelease`; Tink/errorprone dontwarns; serializers + `@SerialName` discriminators kept |

---

## 1. Critical & High-Priority Technical Debt (P0)

> **Sprint A status:** P0-1 ✅ · P0-2 ✅ · P0-3 ✅ · P0-5 ✅ · directory pre-validation ✅.
> **Sprint B status:** P0-4 ✅ (tail-buffer key fallback) · P0-7 ✅ (X-of-Y delete report) ·
> i18n ✅ (en + tr) · file-explorer search ✅ · diff expand/collapse-all ✅.
> **Still open:** P0-6 (instrumentation/SSE device tests) · §5 CI + lint · endpoint probe caching (P2-2).

1. **[RESOLVED] Server-resolution inconsistency (cold-start race).**
   `SessionRepositoryImpl` now resolves through the shared
   `ConnectionRepository.requireActiveServer()` (3 s grace) like every other
   repository; it is free of Android types and covered by JVM MockEngine tests
   (`SessionRepositoryImplTest`).

2. **[RESOLVED] SSE endpoint was a single hardcoded path.**
   `SseEventTransport` now probes `/event` → `/global/event` through an
   `SseConnector` seam and memoizes the winning path per base URL
   (`ConcurrentHashMap`), so reconnects skip probing entirely and a stale
   winner is demoted automatically. Covered by `SseEventTransportTest`.

3. **[RESOLVED] Turn-boundary race between the send long-poll and the stream.**
   `send()` claims a monotonic `turnToken`; only the newest turn may clear
   `isSending`/`isBusy` or run `syncTranscriptFromServer` (token-checked both
   before and after the history fetch). Regression test:
   `late resolution of an older send cannot clobber the newer turn`.

4. **[RESOLVED] Part-merge key degenerates when the server omits `partID`.**
   `MessageAssembler.mergeTargetIndex` now routes blank-`partID` deltas into a
   per-message *tail buffer* (the trailing same-kind part) instead of collapsing
   them into one `id = ""` part; keyed deltas still open distinct parts.
   Covered by three `MessageAssemblerTest` cases.

5. **[RESOLVED] Late subscribers silently lose events (by design, now mitigated).**
   SharedFlow `replay=0`: deltas missed while the chat entry was recreated are
   now reconciled — `ChatViewModel.onResume()` (driven by `LifecycleResumeEffect`
   in `ChatScreen`) re-issues `refresh()` whenever a turn is still busy.

6. **No instrumentation or device-level tests.**
   `SseEventTransport` cannot be exercised via MockEngine (no `SSECapability`); the handshake fix
   was validated by reasoning + sentinel tests only.
   → Add an AndroidTest hitting a local mock-web-server (MockWebServer) SSE endpoint, or a CIO-engine
   based integration test.

7. **[RESOLVED] Sequential batch delete without atomicity.**
   `SessionListViewModel.confirmDelete()` accumulates successes/failures
   independently (one failure never aborts the batch), drops successful rows
   optimistically via the repository cache, and reports a structured
   `DeleteReport(deleted, total)` rendered as "X of Y deleted" — failed rows
   stay visible. `GET /session` still has no pagination (tracked under P1).

---

## 2. Semi-Implemented Features Needing Polish (P1)

- **`FileRepository.diffFile(path)` is dead code** — the diff screen only uses `workingTreeDiff()`.
  Wire per-file deep links (tap a tool card referencing a file → its diff).
- **File explorer**: ✅ name search/filter (`filterFileNodes`) shipped in Sprint B. Remaining: pull-to-refresh, per-directory cache (re-probes the endpoint chain on every navigation), row-level "add to chat" (preview-only today), long-press context menu.
- **Diff viewer**: ✅ expand-all / collapse-all toggle shipped (Sprint B). Remaining: hunks virtualization beyond LazyColumn, copy-patch.
- **[RESOLVED] New-session dialog**: directory is now pre-validated through the
  `/find` endpoint chain before `POST /session`; invalid paths surface as an
  inline field error (`directoryError`) instead of a raw 400 snackbar.
- **Question interactions**: only the first `questions[]` entry is surfaced; no per-question paging,
  no "Other" free-text when `custom` is absent, no re-open affordance beyond the badge for the
  front-most item.
- **Permission dialogs**: `diff` renders as plain `CodePreview`; reuse `DiffViewer`'s color-coded
  hunks by running `UnifiedDiffParser` on it.
- **Session list**: no rename/archive, no pagination, no per-session directory badge.
- **Settings**: latency/server-version only after a manual probe; active model loads once at screen
  entry (no refresh after model switch); no cache-clear action.
- **i18n**: ✅ all screen strings extracted to `values/strings.xml` (English default) +
  `values-tr/strings.xml` (Turkish) in Sprint B. Data-layer friendly exception messages
  (`ApiErrors`, `NoServerConfigured`) intentionally stay hard-coded outside the resource system.
- **`ConnectionStateManager.force()`** debug helper still public — gate behind `BuildConfig.DEBUG`
  or delete.

---

## 3. Performance & Memory (P2)

1. **Compose recomposition scopes**: `MessageList` runs `messages.asReversed()` + the tail-length
   sum on every token; wrap in `remember(state.messages)` and pass minimal lambdas. Add
   `contentType` to the chat `LazyColumn` items (bubble vs step vs tool rows) for view-type reuse.
2. **Endpoint-probe caching**: file ops re-probe 4 candidates per call on servers missing `/fs/*`;
   memoize the winning endpoint per `baseUrl` (in-memory + persisted) — halves round-trips on
   slow LANs.
3. **Baseline profile**: generate one (`androidx.baselineprofile` plugin + macrobenchmark) —
   Compose + Ktor startup paths dominate cold start on low-end devices.
4. **Coroutine cancellation**: `ChatViewModel` collectors live on `viewModelScope` (correct), but
   `OpenCodeStreamClient` owns a raw app-scope `Job` chain; add a supervisor-child registry so
   `stop()` can assert *all* children terminated (currently relies on cancel propagation).
5. **Cache eviction**: session/message caches are unbounded per connection; cap message list length
   (keep last N + `before=` pagination when the server supports it).
6. **DataStore migration**: `PreferenceStore` (SharedPreferences) backs theme + recent dirs; migrate
   to `androidx.datastore` (typed, coroutine-native) once the sync-read at `MainActivity` startup is
   solved with `StartupInitializer`/snapshot flow.
7. **Memory**: `UnifiedDiffParser` snapshots `MutableList` per hunk (fine); SharedFlow buffer 256
   events ≈ negligible; watch `ToolCallPart.args` strings for huge tool inputs — truncate at
   decode (e.g. 64 KB) and stream the rest from `/session/{id}/message`.

---

## 4. Future Feature Expansion (P3)

- **Offline mode**: Room cache of sessions + transcripts, last-known-state banner, queued prompts
  with send-state indicators, sync-on-reconnect.
- **Code rendering**: syntax-highlighted file preview & diff (tree-sitter wasm or a tokenizer lib),
  copy buttons, fenced-code cards inside markdown.
- **Session export/import**: share transcript as Markdown/JSON, import as a new session context.
- **Agent permissions workflow**: persistent rule editor ("allow edit under `src/**`"), permission
  profiles per workspace, audit log of granted/denied decisions.
- **Turn controls**: cancel running turn (`POST /session/{id}/abort`), retry last prompt,
  regenerate, per-message copy, token/cost meter from `step-finish` data.
- **Workspaces UX**: directory picker over `/find` browsing (instead of typed path), pinned
  favorites, per-directory model defaults.
- **Notifications**: long-turn completion notification (WorkManager poll or push relay) when the
  app is backgrounded.
- **Multi-server profiles**: saved servers with quick switch, QR-pairing / mDNS discovery of
  `opencode serve` instances on the LAN.
- **Tablet/large-screen**: Navigation 3 scene (list-detail) using the existing repositories.

---

## 5. Testing & CI Infrastructure

| Gap | Action |
|---|---|
| ~~`SessionRepositoryImpl` untestable on JVM~~ | ✅ Resolved in Sprint A: unified server resolution + `SessionRepositoryImplTest` (MockEngine, incl. delete-404 tolerance) |
| No `./gradlew lint` gate | Add `lint` + `ktlint`/`spotless` to CI; current build is warning-clean, keep it that way |
| No CI workflow | GitHub Actions: `testDebugUnitTest`, `lint`, `assembleDebug`, `assembleRelease` (R8 regression gate), artifact upload + mapping files |
| No screenshot/UI tests | Roborazzi previews for the 5 part cards + dialogs (cheap regression net for Compose) |
| SSE integration coverage | MockWebServer-backed `SseEventTransportTest` (androidTest) incl. keep-alive comments + mid-stream drop |
| Diff parser fuzz-ish coverage | Property-style tests: random valid/invalid hunks → parser never throws |

---

## 6. Release Readiness Checklist

1. Signing config + `versionCode` automation (release currently produces an *unsigned* APK).
2. R8 mapping upload to crash reporting; enable `android.enableR8.fullMode` evaluation.
3. Play Store data-safety form (no telemetry currently — keep it that way; logs are INFO-level, bodies excluded).
4. Cleartext justification for review (LAN self-hosted server; documented in `network_security_config.xml`).
5. Target SDK bump cadence (currently 36) + baseline profile in the release artifact.
6. Privacy: tokens stay in `EncryptedSharedPreferences`; recent-directories list is *not* encrypted —
   acceptable (paths), but call it out in the data-safety declaration.

---

## Suggested Sequencing

**Sprint A (correctness):** ✅ DONE — P0-1, P0-2, P0-3, P0-5, directory pre-validation
(P1) shipped with tests; §5 CI + lint still pending.
**Sprint B (polish):** P1 items (i18n extraction first), P0-4/P0-7, endpoint probe caching (P2-2).
**Sprint C (capability):** offline cache (Room), turn controls (abort/retry), notifications.
**Sprint D (reach):** tablet scenes, multi-server/discovery, release hardening (§6).
