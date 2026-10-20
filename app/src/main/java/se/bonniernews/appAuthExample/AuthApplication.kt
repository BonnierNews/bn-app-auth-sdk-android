package se.bonniernews.appAuthExample

import android.app.Application
import androidx.core.net.toUri
import se.bonniernews.bnappauth_android.BNAppAuth

class AuthApplication: Application() {

    // Dummy values: fill in a real issuer, client and redirect scheme (also in app/build.gradle.kts) to run
    // the example app, but don't commit them.
    private val authScheme = "custom.redirect.scheme"
    private val loginRedirectURL = "$authScheme://www.test.se/login"
    private val logoutRedirectUrl = "$authScheme://www.test.se/logout"

    val config = BNAppAuth.ClientConfiguration(
        issuer = "https://oidc-provider.com/".toUri(),
        clientId = "client-id",
        clientSecret = null,
        loginRedirectURL = loginRedirectURL.toUri(),
        logoutRedirectUrl = logoutRedirectUrl.toUri(),
        customScopes = listOf(
            "profile",
            "offline_access",
            "entitlements",
            "external_ids",
            "email",
            "old_bnidtoken"
        ),
        debuggable = true
    )

    // Dummy values: fill in a site and a client id that id-service issues transfer tokens for.
    val transferTokenResource = "https://www.example.com/".toUri()
    val transferTokenAudience = "site-client-id"

    override fun onCreate() {
        super.onCreate()

    }

}