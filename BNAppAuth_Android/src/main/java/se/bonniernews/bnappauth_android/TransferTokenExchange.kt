package se.bonniernews.bnappauth_android

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlin.coroutines.cancellation.CancellationException

internal class TransferTokenExchange(
    private val client: () -> BNAppAuth.ClientConfiguration?,
    private val freshAccessToken: suspend () -> String?,
    private val serviceConfiguration: suspend () -> AuthorizationServiceConfiguration,
    private val performTokenRequest: suspend (TokenRequest) -> TokenResponse?,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
        const val ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
        const val SESSION_TRANSFER_TOKEN_TYPE = "urn:bonniernews:oauth:token-type:session_transfer_token"
        const val SCOPE = "openid"
        const val INVALID_TARGET = "invalid_target"
        const val TIMEOUT_MS = 2_000L
        const val ORIGIN_WITHOUT_LOGIN_TTL_MS = 24 * 60 * 60 * 1_000L
    }

    private val originsWithoutLogin = mutableMapOf<String, Long>()

    suspend fun transferToken(resource: Uri): String? {
        val host = resource.host
        if (resource.scheme != "https" || host.isNullOrEmpty()) return null
        val port = if (resource.port == -1) 443 else resource.port
        return requestTransferToken("https://${host.lowercase()}:$port", resource.toString())
    }

    private suspend fun requestTransferToken(origin: String, resource: String): String? =
        withContext(Dispatchers.Main) {
            val client = client() ?: return@withContext null
            if ((originsWithoutLogin[origin] ?: 0) > now()) return@withContext null

            try {
                withTimeoutOrNull(timeoutMs) { exchange(client.clientId, resource) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val exception = e as? BnAppAuthException ?: BnAppAuthException(0, e.message, null, null, e)
                if (exception.error != INVALID_TARGET) throw exception
                originsWithoutLogin[origin] = now() + ORIGIN_WITHOUT_LOGIN_TTL_MS
                null
            }
        }

    private suspend fun exchange(clientId: String, resource: String): String? {
        val accessToken = freshAccessToken() ?: return null
        val request = TokenRequest.Builder(serviceConfiguration(), clientId)
            .setGrantType(TOKEN_EXCHANGE_GRANT_TYPE)
            .setScope(SCOPE)
            .setAdditionalParameters(
                mapOf(
                    "subject_token" to accessToken,
                    "subject_token_type" to ACCESS_TOKEN_TYPE,
                    "requested_token_type" to SESSION_TRANSFER_TOKEN_TYPE,
                    "resource" to resource
                )
            )
            .build()
        val response = performTokenRequest(request) ?: return null
        if (response.additionalParameters["issued_token_type"] != SESSION_TRANSFER_TOKEN_TYPE) return null
        return response.accessToken
    }
}
