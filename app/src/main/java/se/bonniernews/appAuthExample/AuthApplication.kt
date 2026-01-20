package se.bonniernews.appAuthExample

import android.app.Application
import androidx.core.net.toUri
import se.bonniernews.bnappauth_android.BNAppAuth

class AuthApplication: Application() {
    
    private val authScheme = "custom.redirect.scheme"
    private val loginRedirectURL = "$authScheme://www.test.se/login"
    private val logoutRedirectUrl = "$authScheme://www.test.se/logout"

    val config = BNAppAuth.ClientConfiguration(
        issuer = "https://oidc-provider.com/".toUri(),
        clientId = "client-id",
        clientSecret = null,
        loginRedirectURL = loginRedirectURL.toUri(),
        logoutRedirectUrl = logoutRedirectUrl.toUri(),
        debuggable = true
    )

    override fun onCreate() {
        super.onCreate()

    }

}