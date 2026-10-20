# BNAppAuth for Android

BNAppAuth is the Bonnier News login SDK for Android. It wraps [AppAuth-Android](https://github.com/openid/AppAuth-Android) and gives the apps one place for everything that talks to id-service: logging in and out, creating accounts, keeping tokens fresh, and handing out transfer tokens that log the user in on other sites.

- [Installation](#installation)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [Logging in and out](#logging-in-and-out)
- [ID tokens](#id-tokens)
- [Transfer tokens](#transfer-tokens)
- [Authentication state](#authentication-state)
- [Migrating from /oidc to /oauth](#migrating-from-oidc-to-oauth)
- [Errors](#errors)
- [Example app](#example-app)
- [License](#license)

## Installation

The library can be installed from GitHub Packages or from JitPack.

### GitHub Packages

The library is published as `com.github.BonnierNews:bn-app-auth-sdk-aar`. Add the repository, with a GitHub user and a token that can read packages:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/BonnierNews/bn-app-auth-sdk-android")
            credentials {
                username = providers.gradleProperty("gpr.usr").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.BonnierNews:bn-app-auth-sdk-aar:<version>")
}
```

### JitPack

JitPack builds the library from a release tag and needs no credentials:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.BonnierNews:bn-app-auth-sdk-android:<tag>")
}
```

## Getting started

Configure the SDK once, for example in your `Application`:

```kotlin
val config = BNAppAuth.ClientConfiguration(
    issuer = Uri.parse("https://id.bonniernews.se/oauth"),
    clientId = "your-client-id",
    loginRedirectURL = Uri.parse("your-app://login-callback"),
    logoutRedirectUrl = Uri.parse("your-app://logout-callback")
)

BNAppAuth.instance.configure(context, config)
```

Login and logout hand you an `Intent` to start. Start it with an activity result launcher and pass the result back to the SDK:

```kotlin
private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    result.data?.let { data ->
        BNAppAuth.instance.continueAuthorization(data) { idToken, exception ->
            // Logged in when idToken is set.
        }
    }
}

BNAppAuth.instance.login { intent, exception ->
    intent?.let { loginLauncher.launch(it) }
}
```

## Configuration

`BNAppAuth.ClientConfiguration` takes the following arguments:

| Argument | Default | Description |
|---|---|---|
| `issuer` | – | The id-service issuer URL. |
| `clientId` | – | The app's OAuth client id. |
| `clientSecret` | `null` | The client secret, for clients that have one. |
| `loginRedirectURL` | – | Where id-service sends the user after logging in. |
| `logoutRedirectUrl` | – | Where id-service sends the user after logging out. |
| `prompt` | `"select_account consent"` | The `prompt` sent with the login request. |
| `customScopes` | `null` | Extra scopes besides `openid`. Add `old_bnidtoken` to also get the legacy `bnIdToken`. |
| `debuggable` | `false` | Logs the SDK's activity, including the tokens themselves. Never enable it in release builds. |
| `useMigration` | `false` | Moves logged-in users from the old `/oidc` issuer to `/oauth`. See [Migrating from /oidc to /oauth](#migrating-from-oidc-to-oauth). |

All requests to id-service time out after 5 seconds. Call `configure` before any other method.

## Logging in and out

| Method | Description |
|---|---|
| `login(loginToken, action, locale, consentId, callback)` | Prepares the login flow and calls back with the `Intent` to start. All arguments except `callback` are optional: `loginToken` logs the user in with a login token, `action` is passed to id-service (for example `create-user`), `locale` sets the language of the login page, and `consentId` passes on the user's consent. |
| `createAccount(locale, consentId, callback)` | Like `login`, but opens the create account page. |
| `logout(): Intent?` | Returns the `Intent` that ends the session at id-service, or `null` when there is no session to end. |
| `continueAuthorization(intent, callback)` | Finishes the flow with the result `Intent`. After a login it calls back with the ID token. After a logout it clears the stored state and calls back with `null`. |

## ID tokens

```kotlin
BNAppAuth.instance.getIdToken { tokenResponse, exception ->
    // tokenResponse is null when the user is logged out or the request failed
}
```

`getIdToken(forceRefresh, getLoginToken, callback)` calls back on the main thread with the current ID token, refreshing the tokens first when needed. While the tokens are valid for more than another minute, it answers without a network call.

- `forceRefresh = true` always refreshes and asks id-service to bypass its cache.
- `getLoginToken = true` refreshes and also returns a login token in `loginToken`.

`BNAppAuth.TokenResponse` contains:

| Property | Description |
|---|---|
| `idToken` | The ID token. |
| `bnIdToken` | The legacy token, when `customScopes` contains `old_bnidtoken`. |
| `loginToken` | A login token, when asked for with `getLoginToken`. |
| `isUpdated` | `true` when the ID token changed since the last call. |

## Transfer tokens

A transfer token is a short-lived token that logs the user in on another site in the same session as the app. The SDK exchanges the user's access token for one at id-service. It never changes the user's own tokens.

There is one suspend function for each kind of target:

```kotlin
// Web views: the id-service client id of the site.
val token = BNAppAuth.instance.getTransferToken(audience = "site-client-id")

// External browsers: the URL that is about to open.
val url = BNAppAuth.instance.getTransferToken(resource = siteUrl)
    ?.let { siteUrl.buildUpon().appendQueryParameter(BNAppAuthImpl.TRANSFER_TOKEN_PARAMETER, it).build() }
    ?: siteUrl
```

Add the token to the URL as `BNAppAuthImpl.TRANSFER_TOKEN_PARAMETER` (`idtransfertoken`). The site exchanges it for its own login.

- **A null token** means there is no token to add right now. Open the URL as it is. That happens when:
  - the user is logged out,
  - id-service has no client for the audience or site (it answers `invalid_target`, and the SDK then skips that audience or site for 24 hours), or
  - id-service didn't answer within 2 seconds.
- **A `BnAppAuthException`** means the exchange failed. Open the URL as it is. The next call tries again.
- **Only https URLs** get a token, since the token ends up in the URL the browser opens.

The functions switch to the main thread themselves, so they can be called from any coroutine. Cancelling the caller's scope cancels the exchange.

## Authentication state

| Member | Description |
|---|---|
| `isAuthorized` | Whether the user is logged in. |
| `clearState()` | Removes the stored state, which logs the user out locally without contacting id-service. |
| `releaseResources()` | Cancels the SDK's ongoing work and releases AppAuth's resources. Call `configure` again before using the SDK afterwards. |

The state is stored in the app's `SharedPreferences`.

## Migrating from /oidc to /oauth

With `useMigration = true`, the first `getIdToken` call after an update exchanges the token from the old `/oidc` issuer for new `/oauth` tokens. It only runs once. If the exchange fails, the user is logged out.

## Errors

Failures are reported as `BnAppAuthException`:

| Property | Description |
|---|---|
| `code` | AppAuth's error code, or `BNAppAuthImpl.LIFECYCLE_ABORT_CODE` (`-999`) when `releaseResources()` was called during the request. |
| `error` | The OAuth error, for example `invalid_grant`. |
| `errorDescription` | A description of the error, when there is one. |
| `errorUri` | A link with more information, when there is one. |
| `rootCause` | The underlying exception. |

## Example app

The `app` module is a Compose app for trying the SDK by hand:

1. **Configuration**: sets up `BNAppAuth` with a client configuration.
2. **Logga in / Logga ut**: starts the login and logout flows and shows the result.
3. **Force refresh**: calls `getIdToken(forceRefresh = true)`.
4. **Get Login Token**: calls `getIdToken(getLoginToken = true)`.
5. **Transfer Token (resource / audience)**: calls `getTransferToken(resource)` or `getTransferToken(audience)` and shows the token, "No token" or the error.

`AuthApplication` holds dummy values for the client configuration and for the site and client id the transfer token buttons use, and `app/build.gradle.kts` holds the redirect scheme. Fill in real ones to run the app, but don't commit them.

## License

MIT License

Copyright (c) 2025 Bonnier News AB

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
