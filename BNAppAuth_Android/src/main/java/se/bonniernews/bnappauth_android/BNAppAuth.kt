package se.bonniernews.bnappauth_android

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import net.openid.appauth.AuthorizationException.AuthorizationRequestErrors.OTHER
import net.openid.appauth.AuthorizationRequest.Prompt.CONSENT
import net.openid.appauth.AuthorizationRequest.Prompt.SELECT_ACCOUNT
import net.openid.appauth.AuthorizationRequest.Scope
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import androidx.core.content.edit

interface BNAppAuth {
    val isAuthorized: Boolean

    fun configure(context: Context, config: ClientConfiguration)
    fun login(
        loginToken: String? = null,
        action: String? = null,
        locale: String? = null,
        callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit
    )

    fun logout(): Intent?
    fun createAccount(
        locale: String? = null,
        callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit
    )

    fun getIdToken(
        forceRefresh: Boolean = false,
        getLoginToken: Boolean = false,
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
        val customScopes: List<String>? = null,
        val debuggable: Boolean = false,
    )

    data class TokenResponse(
        val idToken: String?,
        val bnIdToken: String? = null,
        val loginToken: String? = null,
        val isUpdated: Boolean = false,
    )
}

class BNAppAuthImpl : BNAppAuth {

    companion object {
        const val SHARED_PREFS_NAME = "bn_auth_shared_prefs"
        const val SHARED_PREFS_KEY = "stateJson"
        const val MIGRATION_PREFS_NAME = "bn_migration_prefs"
        const val MIGRATION_PREFS_KEY = "bn_migration_completed"
    }

    @VisibleForTesting
    lateinit var config: BNAppAuth.ClientConfiguration

    @VisibleForTesting
    var authPrefs: SharedPreferences? = null

    var migrationPrefs: SharedPreferences? = null

    @VisibleForTesting
    var authService: AuthorizationService? = null

    @VisibleForTesting
    var authServiceSdk: AuthServiceSdk = AuthServiceSdk()

    @VisibleForTesting
    var currentIdToken: String? = null

    @VisibleForTesting
    var authState: AuthState? = null

    private var isMigrationDone = false
    private val authMutex = Mutex()

    @VisibleForTesting
    val scope = CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private var needsMigration: Boolean
        get() {
            val isCompleted = migrationPrefs?.getBoolean(MIGRATION_PREFS_KEY, false) ?: false
            return !isCompleted
        }
        set(value) {
            migrationPrefs?.edit(commit = true) { putBoolean(MIGRATION_PREFS_KEY, !value) }
        }

    override fun configure(context: Context, config: BNAppAuth.ClientConfiguration) {
        this.config = config
        authPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, MODE_PRIVATE)
        migrationPrefs = context.getSharedPreferences(MIGRATION_PREFS_NAME, MODE_PRIVATE)
        authService = AuthorizationService(context)
        authState = readAuthState()
    }

    override fun login(
        loginToken: String?,
        action: String?,
        locale: String?,
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
            val authorizationRequest =
                authorizationRequest(configuration, loginToken, action, locale)
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

    override fun createAccount(
        locale: String?,
        callback: (intent: Intent?, exception: BnAppAuthException?) -> Unit
    ) {
        login(action = "create-user", locale = locale) { intent, ex ->
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
        getLoginToken: Boolean,
        callback: (tokenResponse: BNAppAuth.TokenResponse?, exception: BnAppAuthException?) -> Unit
    ) {
        if (!::config.isInitialized) {
            Logger.error("configure() must be called before getIdToken()", true)
            callback(null, null)
            return
        }

        val service = authService ?: run {
            Logger.error("performActionWithFreshTokens authService is null", config.debuggable)
            callback(null, BnAppAuthException.convert(OTHER))
            return
        }

        scope.launch {
            authMutex.withLock {

                val migrationStillNeeded = !isMigrationDone && needsMigration
                val idToken = authState?.idToken

                if (migrationStillNeeded && idToken != null) {
                    needsMigration = false
                    isMigrationDone = true
                    val success = performSilentExchange(idToken)
                    if (!success) {
                        clearState()
                        callback(null, BnAppAuthException.convert(OTHER))
                        return@launch
                    }
                }
            }

            if (!isAuthorized) {
                callback(null, null)
                return@launch
            }

            authState?.needsTokenRefresh = forceRefresh || getLoginToken
            val refreshParams = mapOf("issue_login_token" to (getLoginToken).toString())
            authState?.performActionWithFreshTokens(
                service, refreshParams,
                AuthState.AuthStateAction { _, token, ex ->
                    ex?.let {
                        Logger.error("performActionWithFreshTokens=$it", config.debuggable)
                        callback(null, BnAppAuthException.convert(it))
                        return@AuthStateAction
                    }

                    var bnIdToken: String? = null
                    var bnLoginToken: String? = null

                    val params = getAdditionalParameters(authState?.lastTokenResponse)
                    if (params != null) {
                        bnIdToken = params["old_bnidtoken"]
                        bnLoginToken = params["login_token"]
                    }

                    val isUpdated = token != currentIdToken
                    writeAuthState(authState)
                    Logger.debug("idToken=$token", config.debuggable)
                    Logger.debug("accessToken=${authState?.accessToken}", config.debuggable)
                    Logger.debug("refreshToken=${authState?.refreshToken}", config.debuggable)
                    Logger.debug("bnIdToken=$bnIdToken", config.debuggable)
                    Logger.debug("bnLoginToken=$bnLoginToken", config.debuggable)
                    callback(
                        BNAppAuth.TokenResponse(token, bnIdToken, bnLoginToken, isUpdated),
                        null
                    )
                }
            )
        }
    }

    private suspend fun performSilentExchange(oldIdToken: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            exchangeIdTokenAppAuth(
                oldIdToken,
                config.issuer.buildUpon()
                    .appendPath("token")
                    .build()
            ) { token, ex ->
                continuation.resume(ex == null && token != null)
            }
        }

    fun exchangeIdTokenAppAuth(
        oldIdToken: String,
        newExchangeEndpoint: Uri,
        callback: (idToken: String?, exception: BnAppAuthException?) -> Unit
    ) {
        authServiceSdk.fetchFromIssuer(config) { serviceConfiguration, ex ->
            if (ex != null || serviceConfiguration == null) {
                callback(null, BnAppAuthException.convert(ex ?: OTHER))
                return@fetchFromIssuer
            }

            val customConfig = AuthorizationServiceConfiguration(
                serviceConfiguration.authorizationEndpoint,
                newExchangeEndpoint
            )

            val tokenRequest = TokenRequest.Builder(customConfig, config.clientId)
                .setGrantType("urn:ietf:params:oauth:grant-type:token-exchange")
                .setScopes(buildString {
                    append(Scope.OPENID)
                    config.customScopes?.let { scopes ->
                        if (scopes.isNotEmpty()) append(" ${scopes.joinToString(" ")}")
                    }
                })
                .setAdditionalParameters(
                    mapOf(
                        "subject_token" to oldIdToken,
                        "subject_token_type" to "urn:ietf:params:oauth:token-type:id_token"
                    )
                )
                .build()

            performTokenRequest(tokenRequest) { idToken, exception ->
                if (exception != null) {
                    callback(null, exception)
                    return@performTokenRequest
                }

                val tokenResponse = authState?.lastTokenResponse
                if (tokenResponse != null) {
                    val authRequest = AuthorizationRequest.Builder(
                        serviceConfiguration,
                        config.clientId,
                        ResponseTypeValues.CODE,
                        config.loginRedirectURL
                    ).setScopes(buildString {
                        append(Scope.OPENID)
                        config.customScopes?.let { append(" ${it.joinToString(" ")}") }
                    }).build()

                    val authResponse = AuthorizationResponse.Builder(authRequest).build()
                    val ordinaryState = AuthState(authResponse, tokenResponse, null)

                    this.authState = ordinaryState
                    this.currentIdToken = tokenResponse.idToken
                    writeAuthState(ordinaryState)
                }

                callback(idToken, null)
            }
        }
    }

    @VisibleForTesting
    internal fun getAdditionalParameters(resp: TokenResponse?) = resp?.additionalParameters

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
        needsMigration = false
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
        action: String? = null,
        locale: String? = null,
    ): AuthorizationRequest {
        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            config.clientId,
            ResponseTypeValues.CODE,
            config.loginRedirectURL,
        )
            .setPrompt(config.prompt)
            .setScopes(buildString {
                append(Scope.OPENID)
                config.customScopes?.let { scopes ->
                    append(" ${scopes.joinToString(" ")}")
                }
            })
            .apply {
                locale?.let { setUiLocales(it) }
            }

        val additionalParams = mutableMapOf<String, String?>().apply {
            loginToken?.let { put("token", it) }
            action?.let { put("action", it) }
        }

        builder.setAdditionalParameters(additionalParams)

        return builder.build()
    }

    private fun readAuthState(): AuthState? {
        val stateJson = authPrefs?.getString(SHARED_PREFS_KEY, null) ?: return null
        val state = try {
            AuthState.jsonDeserialize(stateJson)
        } catch (_: java.lang.Exception) {
            return null
        }
        currentIdToken = state.idToken
        return state
    }

    @VisibleForTesting
    fun writeAuthState(state: AuthState?) {
        val nonNullState = state ?: return
        this.authState = nonNullState
        currentIdToken = nonNullState.idToken
        authPrefs?.edit(commit = true) { putString(SHARED_PREFS_KEY, nonNullState.jsonSerializeString()) }
    }

    @VisibleForTesting
    override fun clearState() {
        authState = null
        currentIdToken = null
        authPrefs?.edit(commit = true) { remove(SHARED_PREFS_KEY) }
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