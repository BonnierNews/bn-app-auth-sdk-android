package se.bonniernews.bnappauth_android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import net.openid.appauth.AuthState
import net.openid.appauth.AuthState.AuthStateAction
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
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

    private fun configure() {
        bnAppAuth.config = config
        bnAppAuth.authPrefs = authPrefs
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
    }

    @Test
    fun `successful login returns intent`() {
        // Given
        val appAuth = spy(bnAppAuth)
        val intent = fakeIntent(config.loginRedirectURL.toString())
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

    @Test
    fun `getIdToken returns idToken`() {
        // Given
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        appAuth.currentIdToken = "idToken"
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.idToken).thenReturn("idToken")
        whenever(authState.performActionWithFreshTokens(any(), any())).thenAnswer { args ->
            (args.arguments[1] as? AuthStateAction)?.execute(
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

        //Then
        verify(appAuth).writeAuthState(any())
        assertEquals("idToken", tokenResponseTest?.idToken)
        assertEquals(false, tokenResponseTest?.isUpdated)
        assertNull(exceptionTest)
    }

    @Test
    fun `tokenResponseTest is null if AuthStateAction has exception when calling getIdToken`() {
        // Given
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        whenever(authState.isAuthorized).thenReturn(true)

        whenever(authState.performActionWithFreshTokens(any(), any())).thenAnswer { args ->
            (args.arguments[1] as? AuthStateAction)?.execute(null, null, authException)
        }

        // When
        var tokenResponseTest: BNAppAuth.TokenResponse? = null
        var exceptionTest: Exception? = null
        appAuth.getIdToken { tokenResponse, exception ->
            tokenResponseTest = tokenResponse
            exceptionTest = exception
        }

        //Then
        assertNull(tokenResponseTest)
        assertEquals(exceptionTest, bnAppAuthException)
    }

    @Test
    fun `getIdToken with forceRefresh returns tokenResponse with isUpdated=true`() {
        // Given
        val appAuth = spy(bnAppAuth)
        appAuth.authState = authState
        appAuth.currentIdToken = "idTokenOld"
        whenever(authState.isAuthorized).thenReturn(true)
        whenever(authState.createTokenRefreshRequest()).thenReturn(tokenRequest)
        whenever(authState.performActionWithFreshTokens(any(), any())).thenAnswer { args ->
            (args.arguments[1] as? AuthStateAction)?.execute(
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
}