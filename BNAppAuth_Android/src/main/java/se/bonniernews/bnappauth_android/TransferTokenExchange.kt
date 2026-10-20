package se.bonniernews.bnappauth_android

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenRequest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Exchanges the user's access token for short-lived transfer tokens that log the user in on other sites.
 * Kept apart from BNAppAuthImpl, which owns the user's tokens and only lends out a fresh access token.
 */
internal class TransferTokenExchange(
    private val client: () -> BNAppAuth.ClientConfiguration?,
    private val freshAccessToken: suspend () -> String?,
    private val serviceConfiguration: suspend () -> AuthorizationServiceConfiguration,
    private val performTokenRequest: suspend (TokenRequest) -> String?,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
        const val ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
        // id-service requires a scope. Only openid, since the token is for another client than the app.
        const val SCOPE = "openid"
        const val INVALID_TARGET = "invalid_target"
        const val TIMEOUT_MS = 2_000L
        const val TARGET_WITHOUT_LOGIN_TTL_MS = 24 * 60 * 60 * 1_000L
    }

    private val targetsWithoutLogin = mutableMapOf<String, Long>()

    suspend fun transferToken(audience: String): String? =
        requestTransferToken(audience, mapOf("audience" to audience))

    suspend fun transferToken(resource: Uri): String? {
        val host = resource.host
        // The token ends up in the url the browser opens, so it is never added to anything but https.
        if (resource.scheme != "https" || host == null) return null
        return requestTransferToken(host, mapOf("resource" to resource.toString()))
    }

    // Runs on the main thread like the rest of the SDK's AppAuth calls, which also keeps targetsWithoutLogin
    // to a single thread.
    private suspend fun requestTransferToken(targetKey: String, target: Map<String, String>): String? =
        withContext(Dispatchers.Main) {
            val client = client() ?: return@withContext null
            if ((targetsWithoutLogin[targetKey] ?: 0) > now()) return@withContext null

            try {
                withTimeoutOrNull(timeoutMs) { exchange(client.clientId, target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val exception = e as? BnAppAuthException ?: BnAppAuthException(0, e.message, null, null, e)
                if (exception.error != INVALID_TARGET) throw exception
                targetsWithoutLogin[targetKey] = now() + TARGET_WITHOUT_LOGIN_TTL_MS
                null
            }
        }

    private suspend fun exchange(clientId: String, target: Map<String, String>): String? {
        val accessToken = freshAccessToken() ?: return null
        val request = TokenRequest.Builder(serviceConfiguration(), clientId)
            .setGrantType(TOKEN_EXCHANGE_GRANT_TYPE)
            .setScope(SCOPE)
            .setAdditionalParameters(
                mapOf(
                    "subject_token" to accessToken,
                    "subject_token_type" to ACCESS_TOKEN_TYPE
                ) + target
            )
            .build()
        return performTokenRequest(request)
    }
}
