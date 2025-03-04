package se.bonniernews.bnappauth_android

import android.content.Intent
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration

class AuthServiceSdk {
    fun fetchFromIssuer(
        config: BNAppAuth.ClientConfiguration,
        callback: (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit
    ) = AuthorizationServiceConfiguration.fetchFromIssuer(config.issuer, callback)

    fun authorizationResponseFromIntent(intent: Intent) = AuthorizationResponse.fromIntent(intent)

    fun authorizationExceptionFromIntent(intent: Intent) = AuthorizationException.fromIntent(intent)
}