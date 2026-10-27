package se.bonniernews.appAuthExample

import android.app.Application
import androidx.core.net.toUri
import se.bonniernews.bnappauth_android.BNAppAuth

class AuthApplication: Application() {

    // The values come from .env.properties, see app/build.gradle.kts.
    val config = BNAppAuth.ClientConfiguration(
        issuer = BuildConfig.EXAMPLE_ISSUER.toUri(),
        clientId = BuildConfig.EXAMPLE_CLIENT_ID,
        clientSecret = null,
        loginRedirectURL = BuildConfig.EXAMPLE_LOGIN_REDIRECT_URL.toUri(),
        logoutRedirectUrl = BuildConfig.EXAMPLE_LOGOUT_REDIRECT_URL.toUri(),
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

    val transferTokenResource = BuildConfig.EXAMPLE_TRANSFER_TOKEN_RESOURCE.toUri()

    override fun onCreate() {
        super.onCreate()

    }

}