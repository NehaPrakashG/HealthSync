# HealthSync

An Android app built on **Health Connect** that reads, writes and syncs steps and heart-rate data to a backend. It's written in Kotlin with Jetpack Compose, MVVM, Hilt, Room, WorkManager and Retrofit.

The app is a reference for the parts of Health Connect integration that usually go wrong in production: partial and revoked permissions, overlapping data sources, duplicate writes, incremental sync, change-token expiry, background limits and time zones.

## Features

- **Steps:** today's total and a 7-day chart, plus manual step logging
- **Heart rate:** latest reading and today's average/min/max, plus a simulated wearable (stands in for a BLE device)
- **Per-feature permissions:** each data type is connected and rendered independently
- **Background and history access:** requested separately, with feature-availability checks
- **Backend sync:** Health Connect → Room outbox → REST API, every hour or on demand

## Architecture

```
UI (Compose)                 DashboardRoute ── DashboardScreen (stateless, previewable)
                                   │
ViewModel                    DashboardViewModel ── StateFlow<DashboardUiState> + one-off events
                                   │
Domain                       HealthRepository / SyncRepository (interfaces), models, HealthException
                                   │
Data          ┌────────────────────┼───────────────────────┐
              │                    │                       │
     HealthConnectDataSource   Room (outbox)        HealthBackendApi (Retrofit)
     (only class touching      + DataStore
      HealthConnectClient)     (changes tokens)
                                   ▲
Background                   HealthSyncWorker (WorkManager, Hilt)
```

- Only `HealthConnectDataSource` talks to `HealthConnectClient`. It returns plain Kotlin models and throws only domain `HealthException`s.
- The ViewModel depends only on repository interfaces, so it's unit-tested with fakes.
- The UI is split into a stateful **Route** (ViewModel, launchers, lifecycle) and a stateless **Screen** (pure rendering, with previews).

## Problems this app solves

| # | Problem | How it's handled | Where |
|---|---|---|---|
| 1 | Health Connect missing or outdated | `getSdkStatus` → Unsupported / Update required / Available; the client is created lazily | `HealthConnectDataSource.availability` |
| 2 | Partial grants | Per-feature `FeatureUi` state: steps can render while heart rate asks for access | `DashboardViewModel.loadFeature` |
| 3 | Revoked access | Permissions re-checked on every `ON_RESUME`; `SecurityException` → `PermissionMissing` → reconnect UI | `DashboardRoute`, `call {}` |
| 4 | Dialog suppressed after two denials | Denials counted per feature; the CTA switches to "Open Health Connect settings" | `DashboardViewModel.onPermissionResult` |
| 5 | Phone + watch double counting | Totals come from `aggregate` / `aggregateGroupByPeriod`, never summed raw records | `aggregateSteps`, `dailySteps` |
| 6 | Duplicate writes | `clientRecordId` on every write (idempotent); Room keyed by Health Connect id | `insertSteps`, `HealthRecordEntity` |
| 7 | Efficient sync incl. deletions | Changes tokens; upserts and deletions applied to Room; own writes skipped | `SyncRepositoryImpl.incremental` |
| 8 | Token expiry (30 days) | Falls back to a time-range re-read from the last sync (with overlap), deduped by id | `SyncRepositoryImpl.fullImport` |
| 9 | No gap during import | Changes token taken **before** the initial read | `fullImport` |
| 10 | Grant set changes | Tokens stored per type scope (e.g. `HEART_RATE,STEPS`); a new scope triggers a fresh import | `SyncStateStore` |
| 11 | Background restrictions | `READ_HEALTH_DATA_IN_BACKGROUND` plus a feature check; WorkManager with constraints and backoff; foreground sync otherwise | `HealthSyncWorker` |
| 12 | 30-day history limit | `READ_HEALTH_DATA_HISTORY` widens the import window to 365 days | `fullImport` |
| 13 | Time zones / DST | Zone read per call; `ZoneOffset` stored on writes; daily buckets via `LocalDateTime` periods | `TimeProvider`, `dailySteps` |
| 14 | Large result sets | `readRecords` is fully paginated | `readAllPages` |
| 15 | Invalid device data | Validation before writing (range, ordering, no future timestamps) | `RecordValidator` |
| 16 | Unreliable uploads | Room outbox (`PENDING` / `UPLOADED` / `PENDING_DELETE`), batched, retried, server-side dedupe by id | `SyncRepositoryImpl.push` |
| 17 | Concurrent syncs | `Mutex` shared by the worker and "Sync now" | `SyncRepositoryImpl` |
| 18 | Transient errors / rate limits | `RemoteException` / `IOException` → `Transient` → `Result.retry()` with exponential backoff | `HealthSyncWorker` |
| 19 | Play policy | Minimal data types, privacy policy activity (+ Android 14 alias), no health data in analytics | `AndroidManifest.xml` |

## Running it

1. Open in Android Studio (latest stable) and let Gradle sync. Accept any suggested AGP/Kotlin upgrades.
2. Run on an **API 34+ emulator or device**. Health Connect is built in there. On API 33 and lower, install Health Connect from the Play Store.
3. Tap **Connect** on the Steps card, grant access, then **Log 500 steps**.

### Scenarios to try
- **Partial grant:** grant steps, deny heart rate. Steps still render.
- **Lockout:** deny heart rate twice. The button changes to *Open Health Connect settings*.
- **Revoke:** Settings → Security & privacy → Privacy controls → Health Connect → HealthSync → revoke steps, then return to the app.
- **Multi-source:** write steps from another Health Connect app, then tap **Sync now**. They're imported as external records.
- **Background:** tap **Enable extended access**, grant background reads, then trigger the worker from App Inspection → Background Task Inspector.

## Tests

```bash
./gradlew testDebugUnitTest
```
- `SyncRepositoryImplTest`: first import, history window, incremental sync, own-write skipping, deletions, paging, token expiry fallback, upload failure and retry, per-scope tokens
- `DashboardViewModelTest`: availability, partial grants, two-denial lockout, revoke during read, failure isolation, write flow
- `RecordValidatorTest`: input validation

CI runs build and tests on every push (`.github/workflows/android.yml`).

## Backend

`BuildConfig.USE_FAKE_BACKEND = true` uses an in-memory backend, so the app runs end to end. To use a real server, set it to `false` and set `BACKEND_BASE_URL` in `app/build.gradle.kts`.

## Possible next steps
- Real BLE heart-rate client (GATT `0x2A37`) replacing `SimulatedWearable`
- Exercise sessions and sleep
- Room migrations with exported schemas
- Instrumented tests with `connect-testing`'s `FakeHealthConnectClient`
