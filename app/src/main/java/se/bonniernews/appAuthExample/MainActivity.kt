package se.bonniernews.appAuthExample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Dispatcher
import se.bonniernews.appAuthExample.ui.theme.BNAppAuthExampleApp_AndroidTheme
import se.bonniernews.appAuthExample.ui.theme.Black
import se.bonniernews.appAuthExample.ui.theme.Gray
import se.bonniernews.appAuthExample.ui.theme.White
import se.bonniernews.bnappauth_android.BNAppAuth
import kotlin.coroutines.resume


class MainActivity : ComponentActivity() {

    private val appAuth = BNAppAuth.instance
    val authLock = Mutex()
    val dispatcherProvider = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = (applicationContext as? AuthApplication)?.config ?: return
        appAuth.configure(this, config)

        buildLayout()

        appAuth.getIdToken { tokenResponse, exception ->
            exception?.let {
                //TODO: Handle error
                return@getIdToken
            }
            buildLayout(tokenResponse?.idToken)
        }

//        dispatcherProvider.launch {
//            val tokenResponse = getIdToken()
//            CoroutineScope(Dispatchers.Main).launch {
//                buildLayout(tokenResponse?.idToken)
//            }
//        }

        repeat(10) { i ->
            dispatcherProvider.launch {
                val tokenResponse = getIdToken()
                println("TokenResp $i ${tokenResponse?.idToken?.take(10)}")
                CoroutineScope(Dispatchers.Main).launch {
                    buildLayout(tokenResponse?.idToken)
                }
            }
        }

    }

    suspend fun getIdToken(forceRefresh: Boolean = false, getLoginToken: Boolean = false) = authLock.withLock {
        suspendCancellableCoroutine { cont ->
            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                cont.resume(null)
            }

            appAuth.getIdToken(
                forceRefresh = forceRefresh,
                getLoginToken = getLoginToken
            ) { tokenResponse, exception ->
                dispatcherProvider.launch(exceptionHandler) {
                    cont.resume(tokenResponse)
                }
            }
        }
    }

    private fun buildLayout(idToken: String? = null) {
        setContent {
            BNAppAuthExampleApp_AndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showDialog by remember { mutableStateOf(false) }
                    val clipboardManager: androidx.compose.ui.platform.ClipboardManager =
                        LocalClipboardManager.current
                    val state = rememberScrollState()
                    Column(modifier = Modifier.verticalScroll(state = state, enabled = true)) {
                        Text(
                            text = "BNAppAuth",
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary)
                                .fillMaxWidth()
                                .padding(32.dp, 16.dp, 32.dp, 16.dp),
                            fontWeight = Bold,
                            fontSize = 40.sp,
                            color = White,
                        )
                        if (appAuth.isAuthorized) {
                            AuthButton("Logga ut",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp, 16.dp, 32.dp, 0.dp),
                                onClick = {
                                    logout()
                                }
                            )
                            AuthButton("Force refresh",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp, 16.dp, 32.dp, 0.dp),
                                onClick = {
                                    dispatcherProvider.launch {
                                        val tokenResp = getIdToken(forceRefresh = true)
                                        buildLayout(tokenResp?.idToken)
                                    }
                                }
                            )
                            AuthButton("Get Login Token",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp, 16.dp, 32.dp, 0.dp),
                                onClick = {
                                    dispatcherProvider.launch {
                                        val tokenResp = getIdToken(getLoginToken = true)
                                        buildLayout(tokenResp?.loginToken)
                                    }
                                }
                            )
                            AuthClickableText(
                                buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = Bold)) {
                                        append("IdToken:\n")
                                    }
                                    append(idToken)
                                },
                                onClick = {
                                    clipboardManager.setText(
                                        AnnotatedString(idToken ?: "")
                                    )
                                }
                            )
                        } else {
                            AuthButton("Logga in", modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp, 16.dp, 32.dp, 0.dp),
                                onClick = {
                                    login()
                                })
                        }
                    }
                }
            }
        }
    }

    private fun login(loginToken: String? = null) {
        appAuth.login(loginToken) authLogin@{ intent, exception ->
            exception?.let {
                //TODO: Handle error
                return@authLogin
            }
            intent?.let {
                loginActivityIntent.launch(it)
            }
        }
    }

    private fun logout() {
        val intent = appAuth.logout()
        intent?.let {
            logoutActivityIntent.launch(intent)
        }
    }

    private var loginActivityIntent: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                appAuth.continueAuthorization(data) { idToken, exception ->
                    exception?.let {
                        //TODO: Handle error
                        return@continueAuthorization
                    }
                    buildLayout(idToken)
                }
            }
        }

    private var logoutActivityIntent: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                appAuth.continueAuthorization(data) { _, exception ->
                    exception?.let {
                        //TODO: Handle error
                        return@continueAuthorization
                    }
                    buildLayout()
                }
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        appAuth.releaseResources()
    }
}

@Composable
fun AuthClickableText(message: AnnotatedString, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(32.dp, 16.dp, 32.dp, 0.dp)) {
        ClickableText(
            message,
            modifier = Modifier
                .background(Gray)
                .fillMaxWidth()
                .padding(16.dp),
            style = TextStyle(
                color = Black,
                fontSize = 12.sp,
                lineHeight = 14.sp,
            ),
            onClick = { onClick() }
        )
    }
}

@Composable
fun AuthButton(
    name: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier,
        onClick = {
            onClick()
        }
    ) {
        Text(text = name)
    }
}