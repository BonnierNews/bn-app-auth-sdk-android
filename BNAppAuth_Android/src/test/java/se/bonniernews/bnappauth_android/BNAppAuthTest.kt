package se.bonniernews.bnappauth_android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import net.openid.appauth.AuthState
import net.openid.appauth.AuthState.AuthStateAction
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BNAppAuthTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock
    lateinit var authPrefs: SharedPreferences

    @Mock
    lateinit var authEditor: SharedPreferences.Editor

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
        debuggable = true,
        useMigration = true
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        MockitoAnnotations.openMocks(this)

        bnAppAuth = BNAppAuth.instance
        bnAppAuth.authState = null
        bnAppAuth.currentIdToken = null

        mockProperty(bnAppAuth, "isMigrationDone", false)
        mockProperty(bnAppAuth, "scope", CoroutineScope(testDispatcher + SupervisorJob()))

        configure()

        whenever(migrationPrefs.edit()).thenReturn(migrationEditor)
        whenever(migrationEditor.putBoolean(any(), any())).thenReturn(migrationEditor)
        whenever(migrationEditor.remove(any())).thenReturn(migrationEditor) // Add this for safety
        whenever(migrationEditor.commit()).thenReturn(true)

        whenever(authPrefs.edit()).thenReturn(authEditor)
        whenever(authEditor.putString(any(), any())).thenReturn(authEditor)
        whenever(authEditor.remove(any())).thenReturn(authEditor)
        whenever(authEditor.commit()).thenReturn(true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
    fun `consentId is passed to authorizationRequest`() {
        // Given
        val consentId = "consent-id-string"
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
        appAuth.createAccount(consentId = consentId) { loginIntent, _ ->
            loginIntentTest = loginIntent
        }

        // Then
        assertEquals(loginIntentTest, intent)
        verify(appAuth).writeAuthState(any())
        verify(appAuth).authorizationRequest(authorizationServiceConfiguration, null, "create-user", null, consentId)
        verify(authService).getAuthorizationRequestIntent(any())
    }

    @Test
    fun `authorizationRequest is adding consentId as additionalParameter`() {
        // Given
        val consentId = "consent-id-string"
        val appAuth = spy(bnAppAuth)

        // When
        val builder = appAuth.authorizationRequest(
            authorizationServiceConfiguration,
            null,
            "create-user",
            null,
            consentId
        )

        // Then
        assertEquals(builder.additionalParameters["consent_id"], consentId)
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
        appAuth.getIdToken(forceRefresh = true) { tokenResponse, exception ->
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
        appAuth.getIdToken(forceRefresh = true) { tokenResponse, exception ->
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

    @OptIn(ExperimentalCoroutinesApi::class)
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
        appAuth.getIdToken(forceRefresh = true) { _, _ -> }
        advanceUntilIdle()

        // Then
        verify(appAuth).clearState()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `multiple concurrent getIdToken calls during migration only perform one network request`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)

        val realConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://test.se/auth"),
            Uri.parse("https://test.se/token")
        )

        appAuth.authState = authState
        doNothing().whenever(appAuth).writeAuthState(anyOrNull())
        whenever(authState.idToken).thenReturn("old_id_token")
        whenever(migrationPrefs.getBoolean(any(), eq(false))).thenReturn(false)

        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            val callback = args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
            callback(realConfig, null)
        }

        whenever(authService.performTokenRequest(any(), any())).thenAnswer { args ->
            val callback = args.arguments[1] as AuthorizationService.TokenResponseCallback
            callback.onTokenRequestCompleted(tokenResponse, null)
        }

        // When
        val totalCalls = 10
        val results = mutableListOf<BNAppAuth.TokenResponse?>()

        repeat(totalCalls) {
            launch {
                appAuth.getIdToken { response, _ ->
                    synchronized(results) { results.add(response) }
                }
            }
        }

        advanceUntilIdle()

        // Then:
        assertEquals(totalCalls, results.size)
        verify(authService, times(1)).performTokenRequest(any(), any())
        verify(migrationEditor).putBoolean(eq(BNAppAuthImpl.MIGRATION_PREFS_KEY), eq(true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken does not perform migration when useMigration config is false`() = runTest {
        // 1. Create one dispatcher for everyone to share
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        // Given
        val disabledConfig = config.copy(useMigration = false)
        configure(disabledConfig)
        val appAuth = spy(bnAppAuth)

        // 2. Inject a scope that uses the SAME testDispatcher
        mockProperty(appAuth, "scope", CoroutineScope(testDispatcher + SupervisorJob()))

        appAuth.authState = authState
        whenever(authState.idToken).thenReturn("old_id_token")
        whenever(authState.isAuthorized).thenReturn(true)

        // 3. Mock the callback so the await() finishes
        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            val action = args.getArgument<AuthState.AuthStateAction>(2)
            action.execute("fresh_token", "fresh_id_token", null)
        }

        // When
        appAuth.getIdToken { _, _ -> }

        // 4. Advance time
        advanceUntilIdle()

        // Then
        verify(authService, never()).performTokenRequest(any(), any())
        verify(migrationEditor, never()).putBoolean(eq(BNAppAuthImpl.MIGRATION_PREFS_KEY), any())

        // 5. Clean up
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken clears state if migration silent exchange fails`() = runTest {
        // Given
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", CoroutineScope(testDispatcher + SupervisorJob()))

        appAuth.authState = authState
        whenever(authState.idToken).thenReturn("expired_old_token")
        whenever(migrationPrefs.getBoolean(any(), any())).thenReturn(false) // Migration needed

        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            val callback = args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
            callback(null, authException)
        }

        // When
        var exceptionTest: Exception? = null
        appAuth.getIdToken { _, ex -> exceptionTest = ex }
        advanceUntilIdle()

        // Then
        verify(appAuth).clearState()
        assertNotNull(exceptionTest)
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken handles corrupt auth state JSON by treating user as unauthorized`() = runTest {
        // Given
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", CoroutineScope(testDispatcher + SupervisorJob()))

        whenever(authPrefs.getString(eq(BNAppAuthImpl.SHARED_PREFS_KEY), anyOrNull()))
            .thenReturn("NOT_VALID_JSON_!!!")

        val context = RuntimeEnvironment.getApplication()
        appAuth.configure(context, config)

        // When
        var tokenResponseTest: BNAppAuth.TokenResponse? = null
        appAuth.getIdToken { response, _ ->
            tokenResponseTest = response
        }

        advanceUntilIdle()

        // Then
        assertNull(tokenResponseTest)
        assertNull(appAuth.authState)

        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken returns cached token immediately if already fresh`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        appAuth.authState = authState

        mockProperty(appAuth, "isMigrationDone", true)

        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.needsTokenRefresh).thenReturn(false)
        whenever(authState.idToken).thenReturn("cached_id_token")
        whenever(authState.lastTokenResponse).thenReturn(tokenResponse)
        whenever(appAuth.getAdditionalParameters(anyOrNull())).thenReturn(mapOf("login_token" to "cached_login_token"))

        // When
        var result: BNAppAuth.TokenResponse? = null
        appAuth.getIdToken(forceRefresh = false) { response, _ ->
            result = response
        }

        advanceUntilIdle()

        // Then
        assertEquals("cached_id_token", result?.idToken)
        assertNull(result?.loginToken)
        assertEquals(false, result?.isUpdated)

        // Verify that we NEVER reached the refresh logic
        verify(authState, never()).performActionWithFreshTokens(any(), any<Map<String, String>>(), any())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken with forceRefresh=true passes bypass_cache to performActionWithFreshTokens`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        mockProperty(appAuth, "isMigrationDone", true)
        appAuth.authState = authState

        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authState.isAuthorized).thenReturn(true)

        var capturedParams: Map<String, String>? = null
        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            capturedParams = args.getArgument<Map<String, String>>(1)
            (args.arguments[2] as? AuthStateAction)?.execute("accessToken", "idToken", null)
        }

        // When
        appAuth.getIdToken(forceRefresh = true) { _, _ -> }
        advanceUntilIdle()

        // Then
        assertEquals("true", capturedParams?.get("bypass_cache"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken with forceRefresh=false does not pass bypass_cache to performActionWithFreshTokens`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        mockProperty(appAuth, "isMigrationDone", true)
        appAuth.authState = authState

        doNothing().whenever(appAuth).writeAuthState(any())
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.needsTokenRefresh).thenReturn(true) // force refresh path without forceRefresh flag

        var capturedParams: Map<String, String>? = null
        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            capturedParams = args.getArgument<Map<String, String>>(1)
            (args.arguments[2] as? AuthStateAction)?.execute("accessToken", "idToken", null)
        }

        // When
        appAuth.getIdToken(forceRefresh = false, getLoginToken = true) { _, _ -> }
        advanceUntilIdle()

        // Then
        assertFalse(capturedParams?.containsKey("bypass_cache") ?: false)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getIdToken returns LIFECYCLE_ABORT_CODE if authService becomes null during refresh`() = runTest {
        // Given
        val appAuth = spy(bnAppAuth)
        mockProperty(appAuth, "scope", this)
        appAuth.authState = authState

        mockProperty(appAuth, "isMigrationDone", true)
        whenever(authState.isAuthorized).thenReturn(true)

        whenever(authState.performActionWithFreshTokens(any(), any<Map<String, String>>(), any())).thenAnswer { args ->
            // SIMULATE DISPOSE
            appAuth.authService = null
            (args.arguments[2] as? AuthStateAction)?.execute("token", "id", null)
        }

        // When
        var exceptionTest: BnAppAuthException? = null
        appAuth.getIdToken(forceRefresh = true) { _, exception ->
            exceptionTest = exception
        }

        advanceUntilIdle()

        // Then
        assertNotNull(exceptionTest)
        assertEquals(BNAppAuthImpl.LIFECYCLE_ABORT_CODE, exceptionTest?.code)
    }

    @Test
    fun `login returns LIFECYCLE_ABORT_CODE if authService is cleared during discovery`() {
        // Given
        val appAuth = spy(bnAppAuth)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            // SIMULATE DISPOSE
            appAuth.authService = null
            val callback = args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
            callback(authorizationServiceConfiguration, null)
        }

        // When
        var exceptionTest: BnAppAuthException? = null
        appAuth.login(null) { _, exception ->
            exceptionTest = exception
        }

        // Then
        assertNotNull(exceptionTest)
        assertEquals(BNAppAuthImpl.LIFECYCLE_ABORT_CODE, exceptionTest?.code)
    }

    @Test
    fun `exchangeIdTokenAppAuth returns LIFECYCLE_ABORT_CODE if authService is cleared`() {
        // Given
        val appAuth = spy(bnAppAuth)
        whenever(authServiceSdk.fetchFromIssuer(any(), any())).thenAnswer { args ->
            // SIMULATE DISPOSE
            appAuth.authService = null
            val callback = args.getArgument<(AuthorizationServiceConfiguration?, Exception?) -> Unit>(1)
            callback(authorizationServiceConfiguration, null)
        }

        // When
        var exceptionTest: BnAppAuthException? = null
        appAuth.exchangeIdTokenAppAuth("old_token", Uri.parse("https://test.se/token")) { _, exception ->
            exceptionTest = exception
        }

        // Then
        assertNotNull(exceptionTest)
        assertEquals(BNAppAuthImpl.LIFECYCLE_ABORT_CODE, exceptionTest?.code)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        @Throws(Throwable::class)
        override fun evaluate() {
            Dispatchers.setMain(testDispatcher)
            try {
                base.evaluate()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }
}