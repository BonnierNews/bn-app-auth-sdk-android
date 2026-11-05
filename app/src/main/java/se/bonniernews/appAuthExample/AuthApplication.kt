package se.bonniernews.appAuthExample

import android.app.Application
import androidx.core.net.toUri
import se.bonniernews.bnappauth_android.BNAppAuth

class AuthApplication: Application() {

    val config by lazy {
        BNAppAuth.ClientConfiguration(
            issuer = getString(R.string.example_issuer).toUri(),
            clientId = getString(R.string.example_client_id),
            clientSecret = null,
            loginRedirectURL = getString(R.string.example_login_redirect_url).toUri(),
            logoutRedirectUrl = getString(R.string.example_logout_redirect_url).toUri(),
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
    }

    val transferTokenResource by lazy { getString(R.string.example_transfer_token_resource).toUri() }
}
