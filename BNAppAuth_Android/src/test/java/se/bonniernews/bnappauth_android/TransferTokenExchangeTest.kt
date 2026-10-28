package se.bonniernews.bnappauth_android

import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransferTokenExchangeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val client = BNAppAuth.ClientConfiguration(
        issuer = Uri.parse("https://test.se/oauth"),
        clientId = "app",
        loginRedirectURL = Uri.parse("test://login_url"),
        logoutRedirectUrl = Uri.parse("test://logout_url")
    )

    private val serviceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse("https://test.se/oauth/authorize"),
        Uri.parse("https://test.se/oauth/token")
    )

    private val siteUrl = Uri.parse("https://www.site.se/sport/?a=1")

    private val requests = mutableListOf<TokenRequest>()
    private var accessToken: String? = "accessToken"
    private var issuedTokenType = TransferTokenExchange.SESSION_TRANSFER_TOKEN_TYPE
    private var response: suspend (TokenRequest) -> TokenResponse? = { request ->
        TokenResponse.Builder(request)
            .setAccessToken("transferToken")
            .setAdditionalParameters(mapOf("issued_token_type" to issuedTokenType))
            .build()
    }
    private var time = 0L

    private fun makeExchange(configuredClient: BNAppAuth.ClientConfiguration? = client) = TransferTokenExchange(
        client = { configuredClient },
        freshAccessToken = { accessToken },
        serviceConfiguration = { serviceConfiguration },
        performTokenRequest = { request ->
            requests.add(request)
            response(request)
        },
        now = { time }
    )

    private fun tokenError(error: String) = BnAppAuthException.convert(
        AuthorizationException(AuthorizationException.TYPE_OAUTH_TOKEN_ERROR, 2000, error, null, null, null)
    )

    @Test
    fun `transferToken asks for a session transfer token for the url`() = runTest {
        val token = makeExchange().transferToken(siteUrl)

        assertEquals("transferToken", token)
        val request = requests.single()
        assertEquals(serviceConfiguration, request.configuration)
        assertEquals(TransferTokenExchange.TOKEN_EXCHANGE_GRANT_TYPE, request.grantType)
        assertEquals("app", request.clientId)
        assertEquals("openid", request.scope)
        assertEquals(
            mapOf(
                "subject_token" to "accessToken",
                "subject_token_type" to TransferTokenExchange.ACCESS_TOKEN_TYPE,
                "requested_token_type" to TransferTokenExchange.SESSION_TRANSFER_TOKEN_TYPE,
                "resource" to siteUrl.toString()
            ),
            request.additionalParameters
        )
    }

    @Test
    fun `transferToken returns null when id-service issues another kind of token`() = runTest {
        issuedTokenType = "urn:bonniernews:oauth:token-type:login_token"

        assertNull(makeExchange().transferToken(siteUrl))
    }

    @Test
    fun `transferToken returns null without a request before configure`() = runTest {
        assertNull(makeExchange(configuredClient = null).transferToken(siteUrl))
        assertEquals(0, requests.size)
    }

    @Test
    fun `transferToken returns null without a request when logged out`() = runTest {
        accessToken = null

        assertNull(makeExchange().transferToken(siteUrl))
        assertEquals(0, requests.size)
    }

    @Test
    fun `transferToken never issues a token for an http url`() = runTest {
        assertNull(makeExchange().transferToken(Uri.parse("http://www.site.se/sport/")))
        assertEquals(0, requests.size)
    }

    @Test
    fun `transferToken returns null without throwing when id-service has no client`() = runTest {
        response = { throw tokenError(TransferTokenExchange.INVALID_TARGET) }

        assertNull(makeExchange().transferToken(siteUrl))
    }

    @Test
    fun `transferToken remembers origins without id-service login`() = runTest {
        response = { throw tokenError(TransferTokenExchange.INVALID_TARGET) }
        val exchange = makeExchange()

        exchange.transferToken(siteUrl)
        exchange.transferToken(Uri.parse("https://WWW.SITE.SE:443/other/"))
        exchange.transferToken(Uri.parse("https://www.site.se:8443/other/"))

        assertEquals(2, requests.size)
    }

    @Test
    fun `transferToken asks again once an origin without login has expired`() = runTest {
        response = { throw tokenError(TransferTokenExchange.INVALID_TARGET) }
        val exchange = makeExchange()

        exchange.transferToken(siteUrl)
        time = TransferTokenExchange.ORIGIN_WITHOUT_LOGIN_TTL_MS + 1
        exchange.transferToken(siteUrl)

        assertEquals(2, requests.size)
    }

    @Test
    fun `transferToken throws when the exchange fails and asks again next time`() = runTest {
        response = { throw tokenError("invalid_grant") }
        val exchange = makeExchange()

        val exception = runCatching { exchange.transferToken(siteUrl) }.exceptionOrNull()
        runCatching { exchange.transferToken(siteUrl) }

        assertEquals("invalid_grant", (exception as? BnAppAuthException)?.error)
        assertEquals(2, requests.size)
    }

    @Test
    fun `transferToken returns null when id-service is too slow`() = runTest {
        val respond = response
        response = { request ->
            delay(TransferTokenExchange.TIMEOUT_MS * 2)
            respond(request)
        }

        assertNull(makeExchange().transferToken(siteUrl))
    }
}
