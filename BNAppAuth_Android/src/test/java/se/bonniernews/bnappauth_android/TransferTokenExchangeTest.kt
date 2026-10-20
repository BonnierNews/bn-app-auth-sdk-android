package se.bonniernews.bnappauth_android

import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
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
    private var response: suspend () -> String? = { "transferToken" }
    private var time = 0L

    private fun makeExchange(configuredClient: BNAppAuth.ClientConfiguration? = client) = TransferTokenExchange(
        client = { configuredClient },
        freshAccessToken = { accessToken },
        serviceConfiguration = { serviceConfiguration },
        performTokenRequest = { request ->
            requests.add(request)
            response()
        },
        now = { time }
    )

    private fun tokenError(error: String) = BnAppAuthException.convert(
        AuthorizationException(AuthorizationException.TYPE_OAUTH_TOKEN_ERROR, 2000, error, null, null, null)
    )

    @Test
    fun `transferToken for a resource asks for a token for the url`() = runTest {
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
                "resource" to siteUrl.toString()
            ),
            request.additionalParameters
        )
    }

    @Test
    fun `transferToken for an audience asks for a token for the client`() = runTest {
        val token = makeExchange().transferToken("site-web")

        assertEquals("transferToken", token)
        assertEquals(
            mapOf(
                "subject_token" to "accessToken",
                "subject_token_type" to TransferTokenExchange.ACCESS_TOKEN_TYPE,
                "audience" to "site-web"
            ),
            requests.single().additionalParameters
        )
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
    fun `transferToken remembers sites without id-service login`() = runTest {
        response = { throw tokenError(TransferTokenExchange.INVALID_TARGET) }
        val exchange = makeExchange()

        exchange.transferToken(siteUrl)
        exchange.transferToken(Uri.parse("https://www.site.se/other/"))

        assertEquals(1, requests.size)
    }

    @Test
    fun `transferToken remembers audiences without a client`() = runTest {
        response = { throw tokenError(TransferTokenExchange.INVALID_TARGET) }
        val exchange = makeExchange()

        exchange.transferToken("unknown-client")
        exchange.transferToken("unknown-client")

        assertEquals(1, requests.size)
    }

    @Test
    fun `transferToken asks again once a site without login has expired`() = runTest {
        response = { throw tokenError(TransferTokenExchange.INVALID_TARGET) }
        val exchange = makeExchange()

        exchange.transferToken(siteUrl)
        time = TransferTokenExchange.TARGET_WITHOUT_LOGIN_TTL_MS + 1
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
        response = {
            delay(TransferTokenExchange.TIMEOUT_MS * 2)
            "transferToken"
        }

        assertNull(makeExchange().transferToken(siteUrl))
    }
}
