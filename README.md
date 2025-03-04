# BNAppAuth SDK

BNAppAuth is an Android library for handling authentication using the AppAuth library. It provides a simple interface for logging in, logging out, and managing authentication tokens.

## Features
- **Login**: Allows users to authenticate.
- **Account Creation**: Facilitates user registration.
- **Logout**: Log users out.
- **Token Management**: Retrieve and refresh ID tokens.
- **State Management**: Stores and clears authentication state securely.

## Setup

### Dependencies
Make sure to include the necessary dependencies in your `build.gradle` file:

```
dependencies {
    compile 'com.github.BonnierNews:bn-app-auth-aar:{latest version}'
}
```

## Initialization
To use the SDK, you need to first configure it.
```
val clientConfig = BNAppAuth.ClientConfiguration(
    issuer = Uri.parse("https://your-issuer.com"),
    clientId = "your-client-id",
    clientSecret = "your-client-secret", // Optional
    loginRedirectURL = Uri.parse("your-login-redirect-url"),
    logoutRedirectUrl = Uri.parse("your-logout-redirect-url"),
    debuggable = true // Enable for debugging
)

BNAppAuth.instance.configure(context, clientConfig)
```

## Methods

```
configure(context: Context, config: ClientConfiguration)
```
Configures the SDK with the context and the necessary client configuration.

#### Parameters:
- `context`: The application context.
- `config`: The client configuration.

```
login(loginToken: String? = null, action: String? = null, callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit)
```
Starts the login flow by redirecting the user to the authentication provider.

#### Parameters:
- `loginToken` (Optional): A token to pass during login.
- `action` (Optional): An action to pass (e.g., "create-user" for account creation).
- `callback`: A callback function to receive the result, which provides an intent for redirection or an exception.

```
logout(): Intent?
```
Logs the user out by generating the logout request intent.

#### Returns:
- `Intent?`: The intent that can be used to redirect the user to the logout page, or `null` if the SDK is not configured.

```
createAccount(callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit)
```
Initiates the account creation flow.

#### Parameters:
- `callback`: A callback function to receive the result, which provides an intent for redirection or an exception.

```
getIdToken(forceRefresh: Boolean = false, callback: (tokenResponse: TokenResponse?, exception: BnAppAuthException?) -> Unit)
```
Retrieves the ID token, optionally forcing a refresh.

#### Parameters:
- `forceRefresh` (Optional): If `true`, forces the token to refresh even if it's not expired.
- `callback`: A callback function to return the token response or an exception.

#### Returns:
- `TokenResponse?`: The response containing the ID token, or `null` if an error occurred.

```
continueAuthorization(intent: Intent, callback: (idToken: String?, exception: BnAppAuthException?) -> Unit)
```
Continues the authorization flow after a redirect.

#### Parameters:
- `intent`: The intent containing the authorization response from the external provider.
- `callback`: A callback function to return the ID token or an exception.

```
clearState()
```
Clears the authentication state, effectively logging the user out.

```
releaseResources()
```
Releases any resources used by the SDK.

## Exampleapp
The exampleapp in the main module is a sample application demonstrating how to use the BNAppAuth library for authentication in an Android project. It includes examples of configuring the library, initiating login and logout processes, and handling authentication tokens.
1. Configuration: Setting up the BNAppAuth instance with the necessary client configuration.
2. Login: Initiating the login process and handling the result.
3. Logout: Initiating the logout process.
4. Token Management: Retrieving and refreshing ID tokens.
5. State Management: Storing and clearing authentication state.

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