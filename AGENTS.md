# AGENTS.md — BNAppAuth (Android)

Guidance for AI agents working in `bn-app-auth-sdk-android`. Read this first, then the
`README.md` for the public API surface.

## What this is

`BNAppAuth` is an Android library that wraps [openid/AppAuth-Android] to give Bonnier
News apps a small OIDC/OAuth client: login, account creation, logout, ID-token
retrieval/refresh, and secure auth-state storage. It is published as an AAR to GitHub
Packages and consumed by the host apps. This repo also ships a Jetpack Compose example
app for manual testing.

## Layout

This is a two-module Gradle project (`settings.gradle.kts`):

```
BNAppAuth_Android/           # :BNAppAuth_Android — the published library
  src/main/java/se/bonniernews/bnappauth_android/
    BNAppAuth.kt             # Public `interface BNAppAuth` + `BNAppAuth.instance` (BNAppAuthImpl) + ClientConfiguration
    AuthServiceSdk.kt        # Thin wrapper over AppAuth's AuthorizationServiceConfiguration / response parsing
    Logger.kt
  src/test/                  # Robolectric + Mockito unit tests (BNAppAuthTest.kt)
  src/androidTest/           # Instrumented tests
  build.gradle.kts           # library + maven-publish config
app/                         # :app — Compose example app (MainActivity, AuthApplication, ui/theme)
build.gradle.kts             # root: AGP 8.8.2, Kotlin 2.0.0
settings.gradle.kts
.github/workflows/           # run_tests.yml (PR), publish_release.yml (on GitHub Release)
```

## Build & test

Use the Gradle wrapper from the repo root. The library module is the unit of work:

```sh
./gradlew BNAppAuth_Android:clean build      # what CI runs on PRs
./gradlew BNAppAuth_Android:test             # JVM unit tests (Robolectric)
./gradlew assembleDebug                       # build the example app
```

- **JDK 21** is required (CI uses adopt/temurin 21; `sourceCompatibility`/`jvmTarget`
  are 21). `compileSdk = 34`, `minSdk = 21`.
- CI `run_tests.yml` runs `BNAppAuth_Android:clean build` for PRs against `main` and
  `feature/**`. The dev/prod branch model is documented in the workspace root
  `CLAUDE.md` (Android SDK uses `master` for production).
- The build reads GitHub Packages credentials from env vars `GPR_USER` / `GPR_API_KEY`,
  or a local `github.properties` (`gpr.usr` / `gpr.key`). `github.properties` and
  `local.properties` are git-ignored — never commit them.

## Publishing

`publish_release.yml` fires on a published GitHub Release: it runs
`BNAppAuth_Android:clean assembleRelease` then `BNAppAuth_Android:publish`, pushing the
AAR (artifact `bn-app-auth-sdk-aar`, group `com.github.BonnierNews`) to
`maven.pkg.github.com/BonnierNews/bn-app-auth-sdk-android`. `RELEASE_VERSION` comes from
the release tag (`github.ref_name`). Consumers also resolve it via JitPack (see README).

## Architecture notes

- **Single entry point:** `BNAppAuth.instance` (a `BNAppAuthImpl`). Callers must call
  `configure(context, config)` with a `ClientConfiguration` before any auth call.
- **Callback-based API:** `login`, `createAccount`, `getIdToken`, and
  `continueAuthorization` take `(result?, BnAppAuthException?)` callbacks. `login`/
  `createAccount`/`logout` hand back an `Intent` the host activity launches; the
  redirect `Intent` must be fed back into `continueAuthorization`.
- **Coroutines:** internal work runs on coroutine scopes (`Dispatchers`), guarded by a
  `Mutex` for token access. Keep new token reads/writes inside that lock.
- **AppAuth seam:** `AuthServiceSdk` isolates the AppAuth static calls so they can be
  mocked in tests — route new AppAuth interactions through it rather than calling
  `AuthorizationServiceConfiguration` / `AuthorizationResponse` directly from `BNAppAuthImpl`.
- **State storage:** auth state is persisted in `SharedPreferences` (`MODE_PRIVATE`).
- **Resource cleanup:** call `releaseResources()` to tear down the AppAuth
  `AuthorizationService`; the example app shows the lifecycle.
- **Migration:** `ClientConfiguration.useMigration` toggles the `/oidc` → `/oauth`
  migration, mirroring the iOS SDK.

## Conventions

- Public API changes must stay in sync with `README.md` (it documents every method).
- Tests use JUnit4 + Mockito(-kotlin) + Robolectric; mock through the `AuthServiceSdk`
  seam and `@VisibleForTesting` hooks rather than reaching into private state.
- Keep the iOS and Android SDKs conceptually aligned — `ClientConfiguration`, method
  names, and the migration flag intentionally mirror `bn-app-auth-sdk-ios`. If you
  change one platform's public shape, flag the other.

[openid/AppAuth-Android]: https://github.com/openid/AppAuth-Android
