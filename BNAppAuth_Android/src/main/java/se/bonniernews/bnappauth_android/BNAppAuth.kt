package se.bonniernews.bnappauth_android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.VisibleForTesting
import net.openid.appauth.*
import net.openid.appauth.AuthorizationException.AuthorizationRequestErrors.OTHER
import net.openid.appauth.AuthorizationRequest.Prompt.CONSENT
import net.openid.appauth.AuthorizationRequest.Prompt.SELECT_ACCOUNT
import net.openid.appauth.AuthorizationRequest.Scope

interface BNAppAuth {
    val isAuthorized: Boolean

    fun configure(context: Context, config: ClientConfiguration)
    fun login(
        loginToken: String? = null,
        action: String? = null,
        callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit
    )

    fun logout(): Intent?
    fun createAccount(callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit)
    fun getIdToken(
        forceRefresh: Boolean = false,
        callback: (tokenResponse: TokenResponse?, exception: BnAppAuthException?) -> Unit
    )

    fun continueAuthorization(
        intent: Intent,
        callback: (idToken: String?, exception: BnAppAuthException?) -> Unit
    )

    fun clearState()
    fun releaseResources()

    companion object {
        val instance = BNAppAuthImpl()
    }

    data class ClientConfiguration(
        val issuer: Uri,
        val clientId: String,
        val clientSecret: String? = null,
        val loginRedirectURL: Uri,
        val logoutRedirectUrl: Uri,
        val prompt: String = "$SELECT_ACCOUNT $CONSENT",
        val debuggable: Boolean = false,
    )

    data class TokenResponse(
        val idToken: String?,
        val isUpdated: Boolean = false,
    )
}

class BNAppAuthImpl : BNAppAuth {

    companion object {
        const val SHARED_PREFS_NAME = "bn_auth_shared_prefs"
        const val SHARED_PREFS_KEY = "stateJson"
    }

    @VisibleForTesting
    lateinit var config: BNAppAuth.ClientConfiguration

    @VisibleForTesting
    var authPrefs: SharedPreferences? = null

    @VisibleForTesting
    var authService: AuthorizationService? = null

    @VisibleForTesting
    var authServiceSdk: AuthServiceSdk = AuthServiceSdk()

    @VisibleForTesting
    var currentIdToken: String? = null

    @VisibleForTesting
    var authState: AuthState? = null


    override fun configure(context: Context, config: BNAppAuth.ClientConfiguration) {
        this.config = config
        authPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        authService = AuthorizationService(context)
        authState = readAuthState()
    }

    override fun login(
        loginToken: String?,
        action: String?,
        callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit
    ) {
        if (!::config.isInitialized) {
            Logger.error("configure() must be called before login()", true)
            return
        }

        authServiceSdk.fetchFromIssuer(config) { serviceConfiguration, ex ->
            ex?.let {
                Logger.error("login=$it", config.debuggable)
                callback(null, BnAppAuthException.convert(it))
                return@fetchFromIssuer
            }
            val configuration = serviceConfiguration ?: run {
                Logger.error("login no serviceConfiguration", config.debuggable)
                callback(null, BnAppAuthException.convert(OTHER))
                return@fetchFromIssuer
            }
            val authorizationRequest = authorizationRequest(configuration, loginToken, action)
            val requestIntent = try {
                authService?.getAuthorizationRequestIntent(authorizationRequest)
            } catch (e: Exception) {
                Logger.error("getAuthorizationRequestIntent error=$e", config.debuggable)
                callback(null, BnAppAuthException(0, e.message, null, null, e))
                return@fetchFromIssuer
            }

            Logger.debug("login=$requestIntent", config.debuggable)
            val state = AuthState(configuration)
            authState = state
            writeAuthState(state)

            callback(requestIntent, null)
        }
    }

    override fun createAccount(callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit) {
        login(action = "create-user") { intent, ex ->
            ex?.let {
                Logger.error("createAccount=$it", config.debuggable)
                callback(null, it)
                return@login
            }
            callback(intent, null)
        }
    }

    override fun logout(): Intent? {
        if (!::config.isInitialized) {
            Logger.error("configure() must be called before logout()", true)
            return null
        }
        val configuration = authState?.authorizationServiceConfiguration ?: return null
        val endSessionRequest =
            EndSessionRequest.Builder(configuration)
                .setIdTokenHint(authState?.idToken)
                .setPostLogoutRedirectUri(config.logoutRedirectUrl)
                .build()
        val requestIntent = authService?.getEndSessionRequestIntent(endSessionRequest)
        Logger.debug("logout=$requestIntent", config.debuggable)
        return requestIntent
    }

    override val isAuthorized get() = authState?.isAuthorized ?: false

    override fun getIdToken(
        forceRefresh: Boolean,
        callback: (tokenResponse: BNAppAuth.TokenResponse?, exception: BnAppAuthException?) -> Unit
    ) {
        if (!::config.isInitialized) {
            Logger.error("configure() must be called before getIdToken()", true)
            callback(null, null)
            return
        }
        if (!isAuthorized) {
            callback(null, null)
            return
        }
        val service = authService ?: run {
            Logger.error("performActionWithFreshTokens authService is null", config.debuggable)
            callback(null, BnAppAuthException.convert(OTHER))
            return
        }

        authState?.needsTokenRefresh = forceRefresh
        authState?.performActionWithFreshTokens(service,
            AuthState.AuthStateAction { _, token, ex ->
                ex?.let {
                    Logger.error("performActionWithFreshTokens=$it", config.debuggable)
                    callback(null, BnAppAuthException.convert(it))
                    return@AuthStateAction
                }

                val isUpdated = token != currentIdToken
                writeAuthState(authState)
                Logger.debug("idToken=$token", config.debuggable)
                Logger.debug("accessToken=${authState?.accessToken}", config.debuggable)
                Logger.debug("refreshToken=${authState?.refreshToken}", config.debuggable)
                callback(BNAppAuth.TokenResponse(token, isUpdated), null)
            }
        )
    }

    @VisibleForTesting
    fun performTokenRequest(
        request: TokenRequest,
        callback: (idToken: String?, exception: BnAppAuthException?) -> Unit
    ) {
        authService?.performTokenRequest(request) PerformRequest@{ response, exception ->
            authState?.update(response, exception)
            exception?.let {
                Logger.error("performTokenRequest=$it", config.debuggable)
                callback(null, BnAppAuthException.convert(it))
                return@PerformRequest
            }
            writeAuthState(authState)
            callback(authState?.idToken, null)
        }
    }

    override fun continueAuthorization(
        intent: Intent,
        callback: (idToken: String?, exception: BnAppAuthException?) -> Unit
    ) {
        val resp = authServiceSdk.authorizationResponseFromIntent(intent)
        val ex = authServiceSdk.authorizationExceptionFromIntent(intent)

        ex?.let {
            Logger.error("continueAuthorization=$it", config.debuggable)
            callback(null, BnAppAuthException.convert(it))
            return
        }

        if (continueAuthorizationFromLogin(intent.data)) {
            resp?.let {
                authState?.update(it, null)
                writeAuthState(authState)
                performTokenRequest(it.createTokenExchangeRequest()) { token, exception ->
                    callback(token, exception)
                }
            } ?: run {
                Logger.error("continueAuthorization=resp is null", config.debuggable)
                callback(null, BnAppAuthException.convert(OTHER))
            }
        } else {
            clearState()
            callback(null, null)
        }
    }

    private fun continueAuthorizationFromLogin(data: Uri?) =
        data.toString().contains(config.loginRedirectURL.toString())

    @VisibleForTesting
    fun authorizationRequest(
        serviceConfig: AuthorizationServiceConfiguration,
        loginToken: String? = null,
        action: String? = null
    ): AuthorizationRequest {
        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            config.loginRedirectURL,
        )
            .setPrompt(config.prompt)
            .setScopes("${Scope.OPENID} ${Scope.PROFILE} ${Scope.OFFLINE_ACCESS}")

        loginToken?.let {
            builder.setAdditionalParameters(mapOf("token" to it))
        }
        action?.let {
            builder.setAdditionalParameters(mapOf("action" to it))
        }
        return builder.build()
    }

    private fun readAuthState(): AuthState? {
        val stateJson = authPrefs?.getString(SHARED_PREFS_KEY, null) ?: return null
        val state = try {
            AuthState.jsonDeserialize(stateJson)
        } catch(_: java.lang.Exception) {
            return null
        }
        currentIdToken = state.idToken
        return state
    }

    @SuppressLint("ApplySharedPref")
    @VisibleForTesting
    fun writeAuthState(state: AuthState?) {
        state ?: return
        currentIdToken = state.idToken
        authPrefs?.edit()?.putString(SHARED_PREFS_KEY, state.jsonSerializeString())?.commit()
    }

    @VisibleForTesting
    override fun clearState() {
        authState = null
        authPrefs?.edit()?.clear()?.apply()
    }

    override fun releaseResources() {
        authService?.dispose()
        authService = null
    }
}

data class BnAppAuthException(
    val code: Int,
    val errorDescription: String?,
    val error: String?,
    val errorUri: Uri?,
    val rootCause: Throwable,
) : Exception() {
    companion object {
        fun convert(exception: AuthorizationException?) =
            BnAppAuthException(
                code = exception?.code ?: 0,
                errorDescription = exception?.errorDescription,
                error = exception?.error,
                errorUri = exception?.errorUri,
                rootCause = exception ?: Exception()
            )
    }
}