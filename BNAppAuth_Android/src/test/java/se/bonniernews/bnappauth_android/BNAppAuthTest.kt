package se.bonniernews.bnappauth_android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import net.openid.appauth.AuthState
import net.openid.appauth.AuthState.AuthStateAction
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BNAppAuthTest {

    @Mock
    lateinit var authPrefs: SharedPreferences

    @Mock
    lateinit var migrationPrefs: SharedPreferences

    @Mock
    lateinit var migrationEditor: SharedPreferences.Editor

    @Mock
    lateinit var authService: AuthorizationService

    @Mock
    lateinit var authState: AuthState

    @Mock
    lateinit var authServiceSdk: AuthServiceSdk

    @Mock
    lateinit var tokenRequest: TokenRequest

    @Mock
    lateinit var authorizationResponse: AuthorizationResponse

    @Mock
    lateinit var authorizationServiceConfiguration: AuthorizationServiceConfiguration

    @Mock
    lateinit var tokenResponse: TokenResponse

    private lateinit var bnAppAuth: BNAppAuthImpl

    private val config = BNAppAuth.ClientConfiguration(
        issuer = Uri.parse("https://test.se/oidc/"),
        clientId = "app",
        clientSecret = null,
        loginRedirectURL = Uri.parse("test://login_url"),
        logoutRedirectUrl = Uri.parse("test://logout_url"),
        debuggable = true
    )

    private val authException = AuthorizationException(
        AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR,
        500,
        "error",
        "error_description",
        config.loginRedirectURL,
        Throwable()
    )

    private val bnAppAuthException = BnAppAuthException.convert(authException)

    private fun configure(configuration: BNAppAuth.ClientConfiguration = config) {
        bnAppAuth.config = configuration
        bnAppAuth.authPrefs = authPrefs
        bnAppAuth.migrationPrefs = migrationPrefs
        bnAppAuth.authService = authService
        bnAppAuth.authServiceSdk = authServiceSdk
    }

    private fun fakeIntent(url: String) = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(url)
    }

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        bnAppAuth = BNAppAuth.instance
        configure()

        whenever(migrationPrefs.edit()).thenReturn(migrationEditor)
        whenever(migrationEditor.putBoolean(any(), any())).thenReturn(migrationEditor)
        whenever(migrationEditor.apply()).then { /* do nothing */ }
        whenever(migrationEditor.commit()).thenReturn(true)
    }

    @Test
    fun `successful login returns intent`() {
        // Given
        val appAuth = spy(bnAppAuth)
        val intent = fakeIntent(config.loginRedirectURL.toString())
        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authService.getAuthorizationRequestIntent(any())).thenReturn(intent)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
                .invoke(authorizationServiceConfiguration, null)
        }

        // When
        var loginIntentTest: Intent? = null
        appAuth.login(null) { loginIntent, _ ->
            loginIntentTest = loginIntent
        }

        // Then
        assertEquals(loginIntentTest, intent)
        verify(appAuth).writeAuthState(any())
        verify(appAuth).authorizationRequest(authorizationServiceConfiguration, null)
        verify(authService).getAuthorizationRequestIntent(any())
    }

    @Test
    fun `login with exception`() {
        // Given
        val appAuth = spy(bnAppAuth)
        val intent = fakeIntent(config.loginRedirectURL.toString())
        whenever(authService.getAuthorizationRequestIntent(any())).thenReturn(intent)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            args.getArgument<(AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit>(1)
                .invoke(null, authException)
        }

        // When
        appAuth.login(null) { _, _ -> }

        // Then
        verifyNoInteractions(authService)
    }

    @Test
    fun `local from login is passed to authorizationRequest`() {
        // Given
        val locale = "sv-SE"
        val appAuth = spy(bnAppAuth)
        val intent = fakeIntent(config.loginRedirectURL.toString())
        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authService.getAuthorizationRequestIntent(any())).thenReturn(intent)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
                .invoke(authorizationServiceConfiguration, null)
        }

        // When
        var loginIntentTest: Intent? = null
        appAuth.login(null, locale = locale) { loginIntent, _ ->
            loginIntentTest = loginIntent
        }

        // Then
        assertEquals(loginIntentTest, intent)
        verify(appAuth).writeAuthState(any())
        verify(appAuth).authorizationRequest(authorizationServiceConfiguration, null, null, locale)
        verify(authService).getAuthorizationRequestIntent(any())
    }

    @Test
    fun `successful createAccount returns intent`() {
        // Given
        val appAuth = spy(bnAppAuth)
        val intent = fakeIntent(config.loginRedirectURL.toString())
        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authService.getAuthorizationRequestIntent(any())).thenReturn(intent)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
                .invoke(authorizationServiceConfiguration, null)
        }

        // When
        var loginIntentTest: Intent? = null
        appAuth.createAccount { loginIntent, _ ->
            loginIntentTest = loginIntent
        }

        // Then
        assertEquals(loginIntentTest, intent)
        verify(appAuth).writeAuthState(any())
        verify(appAuth).authorizationRequest(authorizationServiceConfiguration, null, "create-user")
        verify(authService).getAuthorizationRequestIntent(any())
    }

    @Test
    fun `locale is passed to authorizationRequest`() {
        // Given
        val locale = "sv-SE"
        val appAuth = spy(bnAppAuth)
        val intent = fakeIntent(config.loginRedirectURL.toString())
        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authService.getAuthorizationRequestIntent(any())).thenReturn(intent)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
                .invoke(authorizationServiceConfiguration, null)
        }

        // When
        var loginIntentTest: Intent? = null
        appAuth.createAccount(locale = locale) { loginIntent, _ ->
            loginIntentTest = loginIntent
        }

        // Then
        assertEquals(loginIntentTest, intent)
        verify(appAuth).writeAuthState(any())
        verify(appAuth).authorizationRequest(authorizationServiceConfiguration, null, "create-user", locale)
        verify(authService).getAuthorizationRequestIntent(any())
    }

    @Test
    fun `authorizationRequest is adding locale as additionalParameter`() {
        // Given
        val locale = "sv-SE"
        val appAuth = spy(bnAppAuth)

        // When
        val builder = appAuth.authorizationRequest(authorizationServiceConfiguration, null, "create-user", locale)

        // Then
        assertEquals(builder.uiLocales, locale)
    }

    @Test
    fun `logout returns logout intent`() {
        // Given
        val intent = fakeIntent(config.logoutRedirectUrl.toString())
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        whenever(authState.authorizationServiceConfiguration).thenReturn(
            authorizationServiceConfiguration
        )
        whenever(authService.getEndSessionRequestIntent(any())).thenReturn(intent)

        // When
        val logoutIntent = appAuth.logout()

        // Then
        assertEquals(logoutIntent, intent)
    }

    @Test
    fun `continueAuthorization from login`() {
        // Given
        val intent = fakeIntent(config.loginRedirectURL.toString())
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authState.idToken).thenReturn("idToken")
        whenever(authorizationResponse.createTokenExchangeRequest()).thenReturn(tokenRequest)
        whenever(authServiceSdk.authorizationResponseFromIntent(intent)).thenReturn(
            authorizationResponse
        )
        whenever(authService.performTokenRequest(any(), any())).thenAnswer { args ->
            (args.arguments[1] as? AuthorizationService.TokenResponseCallback)?.onTokenRequestCompleted(
                tokenResponse,
                null
            )
        }

        // When
        var idTokenTest: String? = null
        appAuth.continueAuthorization(intent) { idToken, _ ->
            idTokenTest = idToken
        }

        // Then
        assertTrue(idTokenTest == "idToken")
        verify(authState).update(authorizationResponse, null)
        verify(authState).update(tokenResponse, null)
        verify(appAuth, times(2)).writeAuthState(any())
    }

    @Test
    fun `continueAuthorization from logout`() {
        // Given
        val intent = fakeIntent(config.logoutRedirectUrl.toString())
        val appAuth = spy(bnAppAuth)
        doNothing().whenever(appAuth).clearState()
        whenever(authServiceSdk.authorizationResponseFromIntent(intent)).thenReturn(
            authorizationResponse
        )

        // When
        appAuth.continueAuthorization(intent) { _, _ -> }

        //Then
        verify(appAuth).clearState()
    }

    @Test
    fun `continueAuthorization with exception`() {
        // Given
        val intent = fakeIntent(config.loginRedirectURL.toString())
        val appAuth = spy(bnAppAuth)
        whenever(authServiceSdk.authorizationExceptionFromIntent(intent)).thenReturn(
            authException
        )

        // When
        var idTokenTest: String? = null
        var exceptionTest: Exception? = null
        appAuth.continueAuthorization(intent) { idToken, exception ->
            idTokenTest = idToken
            exceptionTest = exception
        }

        assertTrue(idTokenTest == null)
        assertEquals(exceptionTest, bnAppAuthException)
    }

    @Test
    fun `assert isAuthorized is true when authState isAuthorized is true`() {
        // Given
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        whenever(authState.isAuthorized).thenReturn(true)

        // Then
        assertTrue(appAuth.isAuthorized)
    }

    @Test
    fun `when calling getIdToken both idToken and exception is null if not authorized`() {
        // Given
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        whenever(authState.isAuthorized).thenReturn(false)

        // When
        var idTokenTest: String? = null
        var exceptionTest: Exception? = null
        appAuth.getIdToken { tokenResponse, exception ->
            idTokenTest = tokenResponse?.idToken
            exceptionTest = exception
        }

        // Then
        assertNull(idTokenTest)
        assertNull(exceptionTest)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken returns idToken`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        appAuth.authState = authState
        appAuth.currentIdToken = "idToken"

        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(migrationPrefs.getBoolean(BNAppAuthImpl.MIGRATION_PREFS_KEY, false)).thenReturn(true)
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.idToken).thenReturn("idToken")
        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            (args.arguments[2] as? AuthStateAction)?.execute(
                "accessToken",
                "idToken",
                null
            )
        }

        // When
        var tokenResponseTest: BNAppAuth.TokenResponse? = null
        var exceptionTest: Exception? = null
        appAuth.getIdToken { tokenResponse, exception ->
            tokenResponseTest = tokenResponse
            exceptionTest = exception
        }

        advanceUntilIdle()

        //Then
        verify(appAuth).writeAuthState(any())
        assertEquals("idToken", tokenResponseTest?.idToken)
        assertEquals(false, tokenResponseTest?.isUpdated)
        assertNull(exceptionTest)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `tokenResponseTest is null if AuthStateAction has exception when calling getIdToken`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        appAuth.authState = authState
        whenever(authState.isAuthorized).thenReturn(true)

        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            (args.arguments[2] as? AuthStateAction)?.execute(null, null, authException)
        }

        // When
        var tokenResponseTest: BNAppAuth.TokenResponse? = null
        var exceptionTest: Exception? = null
        appAuth.getIdToken { tokenResponse, exception ->
            tokenResponseTest = tokenResponse
            exceptionTest = exception
        }

        advanceUntilIdle()

        //Then
        assertNull(tokenResponseTest)
        assertEquals(exceptionTest, bnAppAuthException)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken with forceRefresh returns tokenResponse with isUpdated=true`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        appAuth.authState = authState
        appAuth.currentIdToken = "idTokenOld"

        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.createTokenRefreshRequest()).thenReturn(tokenRequest)
        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            (args.arguments[2] as? AuthStateAction)?.execute(
                "idTokenNew",
                "idToken",
                null
            )
        }

        // When
        var tokenResponseTest: BNAppAuth.TokenResponse? = null
        var exceptionTest: Exception? = null
        appAuth.getIdToken(true) { tokenResponse, exception ->
            tokenResponseTest = tokenResponse
            exceptionTest = exception
        }

        advanceUntilIdle()

        //Then
        verify(appAuth).writeAuthState(any())
        assertEquals("idToken", tokenResponseTest?.idToken)
        assertEquals(true, tokenResponseTest?.isUpdated)
        assertNull(exceptionTest)
    }

    @Test
    fun `idToken is null if getFreshIdToken returns exception`() {
        // Given
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.createTokenRefreshRequest()).thenReturn(tokenRequest)
        whenever(authService.performTokenRequest(any(), any())).thenAnswer { args ->
            (args.arguments[1] as? AuthorizationService.TokenResponseCallback)?.onTokenRequestCompleted(
                null,
                authException
            )
        }

        // When
        var idTokenTest: String? = null
        var exceptionTest: Exception? = null
        appAuth.performTokenRequest(mock()) { idToken, exception ->
            idTokenTest = idToken
            exceptionTest = exception
        }

        //Then
        verify(appAuth, times(0)).writeAuthState(any())
        assertEquals(exceptionTest, bnAppAuthException)
        assertNull(idTokenTest)
    }

    @Test
    fun `ActivityNotFoundException is handled in getAuthorizationRequestIntent`() {
        // Given
        val appAuth = spy(bnAppAuth)
        whenever(authService.getAuthorizationRequestIntent(any())).thenThrow(
            ActivityNotFoundException()
        )
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
                .invoke(authorizationServiceConfiguration, null)
        }

        // When
        var exceptionTest: BnAppAuthException? = null
        appAuth.login(null) { _, exception ->
            exceptionTest = exception
        }

        // Then
        assert(exceptionTest?.rootCause is ActivityNotFoundException)
        verify(appAuth, never()).writeAuthState(any())
    }

    @Test
    fun `assert that customScopes is used when set`() {
        // Given
        val config = BNAppAuth.ClientConfiguration(
            issuer = Uri.parse("https://test.se/oidc/"),
            clientId = "app",
            clientSecret = null,
            loginRedirectURL = Uri.parse("test://login_url"),
            logoutRedirectUrl = Uri.parse("test://logout_url"),
            debuggable = true,
            customScopes = listOf("profile", "offline_access", "customScope1", "customScope2")
        )
        configure(config)
        val locale = "sv-SE"
        val appAuth = spy(bnAppAuth)

        // When
        val builder = appAuth.authorizationRequest(authorizationServiceConfiguration, null, "create-user", locale)

        // Then
        assertEquals(builder.scope, "openid profile offline_access customScope1 customScope2")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken sets old_bnidtoken if customScopes has old_bnidtoken`() = runTest {
        val config = BNAppAuth.ClientConfiguration(
            issuer = Uri.parse("https://test.se/oidc/"),
            clientId = "app",
            clientSecret = null,
            loginRedirectURL = Uri.parse("test://login_url"),
            logoutRedirectUrl = Uri.parse("test://logout_url"),
            debuggable = true,
            customScopes = listOf("old_bnidtoken")
        )
        configure(config)
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        appAuth.authState = authState

        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(appAuth.getAdditionalParameters(anyOrNull())).thenReturn(mapOf("old_bnidtoken" to "old_bnidtoken"))

        whenever(
            authState.performActionWithFreshTokens(
                any(),
                any<Map<String, String>>(),
                any()
            )
        ).thenAnswer { args ->
            (args.arguments[2] as? AuthStateAction)?.execute(
                "accessToken",
                "idToken",
                null
            )
        }

        // When
        var resultTokenResponse: BNAppAuth.TokenResponse? = null
        appAuth.getIdToken { response, _ ->
            resultTokenResponse = response
        }

        advanceUntilIdle()

        // Then
        assertEquals("old_bnidtoken", resultTokenResponse?.bnIdToken)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken with getLoginToken=true sets login_token from response`() = runTest {
        // Given
        val config = BNAppAuth.ClientConfiguration(
            issuer = Uri.parse("https://test.se/oidc/"),
            clientId = "app",
            clientSecret = null,
            loginRedirectURL = Uri.parse("test://login_url"),
            logoutRedirectUrl = Uri.parse("test://logout_url"),
            debuggable = true,
        )
        configure(config)
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        appAuth.authState = authState

        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(appAuth.getAdditionalParameters(anyOrNull())).thenReturn(mapOf("login_token" to "login_token"))

        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            (args.arguments[2] as? AuthStateAction)?.execute(
                "accessToken",
                "idToken",
                null
            )
        }

        // When
        var resultTokenResponse: BNAppAuth.TokenResponse? = null
        appAuth.getIdToken(getLoginToken = true) { response, _ ->
            resultTokenResponse = response
        }

        advanceUntilIdle()

        // Then
        assertEquals("login_token", resultTokenResponse?.loginToken)
    }

    @Test
    fun `migration creates synthetic auth state correctly`() = runTest {
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)

        val realConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://test.se/auth"),
            Uri.parse("https://test.se/token")
        )

        appAuth.authState = authState
        whenever(authState.lastTokenResponse).thenReturn(tokenResponse)
        doNothing().whenever(appAuth).writeAuthState(anyOrNull())

        whenever(authState.authorizationServiceConfiguration).thenReturn(realConfig)
        whenever(authState.idToken).thenReturn("old_id_token")

        whenever(migrationPrefs.getBoolean(any(), any())).thenReturn(false)

        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            val callback = args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
            callback(realConfig, null)
        }

        whenever(authService.performTokenRequest(any(), any())).thenAnswer { args ->
            (args.arguments[1] as AuthorizationService.TokenResponseCallback)
                .onTokenRequestCompleted(tokenResponse, null)
        }

        // When
        appAuth.getIdToken { _, _ -> }
        advanceUntilIdle()

        // Then
        verify(appAuth, times(2)).writeAuthState(anyOrNull())
        verify(migrationEditor).putBoolean(eq(BNAppAuthImpl.MIGRATION_PREFS_KEY), eq(true))
        verify(authState).update(eq(tokenResponse), anyOrNull())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `migration failure clears state and returns exception`() = runTest {
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)

        val realConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://test.se/auth"),
            Uri.parse("https://test.se/token")
        )

        doNothing().whenever(appAuth).clearState()
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.idToken).thenReturn("old_id_token")
        appAuth.authState = authState
        whenever(migrationPrefs.getBoolean(any(), any())).thenReturn(false)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
                .invoke(realConfig, null)
        }

        whenever(authService.performTokenRequest(any(), any())).thenAnswer { args ->
            val callback = args.arguments[1] as AuthorizationService.TokenResponseCallback
            callback.onTokenRequestCompleted(null, authException)
        }

        // When
        appAuth.getIdToken { _, _ -> }
        advanceUntilIdle()

        // Then
        verify(appAuth).clearState()
    }

    fun mockProperty(obj: Any, propertyName: String, value: Any) {
        val field = obj.javaClass.getDeclaredField(propertyName)
        field.isAccessible = true

        try {
            val modifiersField = field.javaClass.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
        } catch (_: Exception) {}

        field.set(obj, value)
    }
}