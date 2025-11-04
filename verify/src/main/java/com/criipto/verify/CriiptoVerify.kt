package com.criipto.verify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.browser.auth.AuthTabIntent
import androidx.browser.auth.AuthTabIntent.AuthResult
import androidx.browser.customtabs.CustomTabsClient
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.auth0.jwk.Jwk
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.criipto.verify.eid.DanishMitID
import com.criipto.verify.eid.EID
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.AuthorizationManagementRequest
import net.openid.appauth.AuthorizationManagementResponse
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.EndSessionResponse
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.browser.AnyBrowserMatcher
import net.openid.appauth.browser.BrowserMatcher
import net.openid.appauth.browser.Browsers
import net.openid.appauth.browser.VersionedBrowserMatcher
import java.security.interfaces.RSAPublicKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes

const val TAG = "CriiptoVerify"

private const val BRAVE = "com.brave.browser"
private const val EDGE = "com.microsoft.emmx"

private enum class TabType {
  CustomTab(),
  AuthTab(),
}

sealed class CustomTabResult {
  class CustomTabSuccess(
    val resultUri: Uri,
  ) : CustomTabResult()

  class CustomTabFailure(
    val ex: AuthorizationException,
  ) : CustomTabResult()
}

class CriiptoVerify private constructor(
  private val clientID: String,
  private val domain: Uri,
  private val redirectUri: Uri,
  private val appSwitchUri: Uri,
  private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
  private val httpClient =
    HttpClient(Android) {
      expectSuccess = true
      install(ContentNegotiation) {
        json()
      }
    }

  /**
   * The AppAuth authorization service, which provides helper methods for OIDC operations, and manages the browser.
   * The service needs access to the activity, so it is initialized in `onCreate`.
   */
  private lateinit var authorizationService: AuthorizationService

  /**
   * The OIDC service configuration for your Criipto domain. Loaded in `create()`
   */
  private lateinit var serviceConfiguration: AuthorizationServiceConfiguration

  /**
   * The type of browser tab to user, either custom tab or auth tab.
   * Determining the supported browser requires an activity, so this is set in `onCreate`.
   */
  private lateinit var tabType: TabType

  /**
   * The JWKS (JSON Web Key Set) used by Criipto to sign the returned JWT. Loaded in `create()`
   * */
  private var jwks: List<Jwk>? = null

  /**
   * An activity result launcher, used to a launch an auth tab intent and listen for the result.
   * See https://developer.android.com/training/basics/intents/result
   */
  private var authTabIntentLauncher: ActivityResultLauncher<Intent?>

  /**
   * An activity result launcher, used to a launch a custom tab intent and listen for the result.
   * See https://developer.android.com/training/basics/intents/result
   */
  private var customTabIntentLauncher:
    ActivityResultLauncher<Pair<AuthorizationManagementRequest, Uri>>

  companion object {
    suspend fun create(
      clientID: String,
      domain: Uri,
      redirectUri: Uri = "$domain/android/callback".toUri(),
      appSwitchUri: Uri = "$domain/android/callback/appswitch".toUri(),
      activity: ComponentActivity,
    ): CriiptoVerify {
      val criiptoVerify = CriiptoVerify(clientID, domain, redirectUri, appSwitchUri, activity)

      coroutineScope {
        async { criiptoVerify.fetchCriiptoOIDCConfiguration() }
        async { criiptoVerify.fetchCriiptoJWKS() }
      }.await()

      return criiptoVerify
    }
  }

  init {
    for (uri in listOf(domain, redirectUri, appSwitchUri)) {
      if (uri.scheme != "https") {
        throw Exception("domain, redirectUri and appSwitchUri must be HTTPS URIs")
      }
    }

    activity.lifecycle.addObserver(this)

    authTabIntentLauncher =
      AuthTabIntent.registerActivityResultLauncher(activity, this::handleAuthTabResult)

    customTabIntentLauncher =
      activity.registerForActivityResult(
        object :
          ActivityResultContract<Pair<AuthorizationManagementRequest, Uri>, CustomTabResult>() {
          override fun createIntent(
            context: Context,
            input: Pair<AuthorizationManagementRequest, Uri>,
          ): Intent {
            Log.d(TAG, "Creating custom tab intent")

            var (request, uri) = input

            val customTabIntent =
              authorizationService
                .createCustomTabsIntentBuilder(uri)
                .setSendToExternalDefaultHandlerEnabled(true)
                .build()

            return when (request) {
              is AuthorizationRequest ->
                AuthorizationManagementActivity.createStartForResultIntent(
                  activity,
                  request,
                  customTabIntent.intent
                    .setData(uri),
                )

              is EndSessionRequest ->
                authorizationService.getEndSessionRequestIntent(
                  request,
                  customTabIntent,
                )

              else -> throw Exception("Unsupported request type $input")
            }
          }

          override fun parseResult(
            resultCode: Int,
            intent: Intent?,
          ): CustomTabResult {
            Log.d(TAG, "Parsing result from custom tab intent")
            val ex = AuthorizationException.fromIntent(intent)

            return if (ex != null) {
              CustomTabResult.CustomTabFailure(ex)
            } else {
              CustomTabResult.CustomTabSuccess(intent!!.data!!)
            }
          }
        },
        this::handleCustomTabResult,
      )
  }

  override fun onCreate(owner: LifecycleOwner) {
    tabType =
      if (CustomTabsClient.isAuthTabSupported(
          activity,
          Browsers.Chrome.PACKAGE_NAME,
        )
      ) {
        TabType.AuthTab
      } else {
        TabType.CustomTab
      }

    val browserMatcher =
      when (tabType) {
        // When using an auth tab, we do not need the internal browser matching logic from appauth
        TabType.AuthTab -> {
          Log.i(TAG, "Using Chrome with auth tab")
          BrowserMatcher { false }
        }
        TabType.CustomTab -> {
          val preferredBrowser =
            listOf(
              Pair(Browsers.Chrome.PACKAGE_NAME, VersionedBrowserMatcher.CHROME_CUSTOM_TAB),
              Pair(Browsers.SBrowser.PACKAGE_NAME, VersionedBrowserMatcher.SAMSUNG_CUSTOM_TAB),
              Pair(BRAVE, BrowserMatcher { it.packageName === BRAVE }),
              Pair(EDGE, BrowserMatcher { it.packageName === EDGE }),
            ).find {
              // Find the first of our preferred browsers, which is able to open a custom tab.
              CustomTabsClient.getPackageName(
                activity,
                listOf(it.first),
                true,
              ) != null
            }

          val (browserName, browserMatcher) =
            // If we found any of our preferred browsers above, use that.
            preferredBrowser
              // Otherwise, fall back to the default browser
              ?: Pair(
                CustomTabsClient.getPackageName(
                  activity,
                  emptyList(),
                )!!,
                AnyBrowserMatcher.INSTANCE,
              )

          // TODO: error if there are no browsers that can handle custom tabs!

          Log.i(TAG, "Using $browserName with custom tab")
          browserMatcher
        }
      }

    authorizationService =
      AuthorizationService(
        activity,
        AppAuthConfiguration
          .Builder()
          .setBrowserMatcher(
            browserMatcher,
          ).build(),
      )
  }

  override fun onDestroy(owner: LifecycleOwner) {
    authorizationService.dispose()
    httpClient.close()
  }

  private fun handleResultUri(uri: Uri) = browserFlowContinuation?.resume(uri)

  private fun handleException(ex: Exception) = browserFlowContinuation?.resumeWithException(ex)

  private fun handleCustomTabResult(result: CustomTabResult) {
    Log.i(TAG, "Handling custom tab result $result")

    when (result) {
      is CustomTabResult.CustomTabFailure -> handleException(result.ex)
      is CustomTabResult.CustomTabSuccess -> handleResultUri(result.resultUri)
    }
  }

  private fun handleAuthTabResult(result: AuthResult) {
    Log.i(TAG, "Handling auth tab result. Code: ${result.resultCode}")

    when (result.resultCode) {
      AuthTabIntent.RESULT_OK -> handleResultUri(result.resultUri!!)
      AuthTabIntent.RESULT_CANCELED -> handleException(Exception("RESULT_CANCELED"))
      AuthTabIntent.RESULT_UNKNOWN_CODE -> handleException(Exception("RESULT_UNKNOWN_CODE"))
      AuthTabIntent.RESULT_VERIFICATION_FAILED ->
        handleException(
          Exception("RESULT_VERIFICATION_FAILED"),
        )
      AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT ->
        handleException(
          Exception("RESULT_VERIFICATION_TIMED_OUT"),
        )
    }
  }

  suspend fun login(eid: EID<*>): String {
    Log.i(TAG, "Starting login with ${eid.acrValue}")

    val loginHints =
      (
        mutableSetOf(
          "mobile:continue_button:never",
        ) + eid.loginHints
      ) as MutableSet<String>

    if (eid is DanishMitID) {
      loginHints.add("appswitch:android")
      loginHints.add("appswitch:resumeUrl:$appSwitchUri")
    }

    val scopes = eid.scopes + listOf("openid")

    val authorizationRequest =
      AuthorizationRequest
        .Builder(
          serviceConfiguration,
          clientID,
          ResponseTypeValues.CODE,
          redirectUri,
        ).setScope(scopes.joinToString(" "))
        .setPrompt("login")
        .setAdditionalParameters(mapOf("acr_values" to eid.acrValue))
        .setLoginHint(loginHints.joinToString(" "))
        .build()

    val parRequestUri = pushAuthorizationRequest(authorizationRequest)

    val callbackUri = launchBrowser(authorizationRequest, parRequestUri)

    return exchangeCode(authorizationRequest, callbackUri)
  }

  private suspend fun exchangeCode(
    request: AuthorizationRequest,
    callbackUri: Uri,
  ): String =
    suspendCoroutine { continuation ->
      val response =
        AuthorizationResponse
          .Builder(request)
          .fromUri(callbackUri)
          .build()

      if (!validateState(request, response)) {
        continuation.resumeWithException(Exception("State mismatch"))
        return@suspendCoroutine
      }

      authorizationService.performTokenRequest(
        response.createTokenExchangeRequest(),
      ) { tokenResponse, ex ->
        if (ex != null) {
          continuation.resumeWithException(ex)
          return@performTokenRequest
        }

        // From TokenResponseCallback - Exactly one of `response` or `ex` will be non-null. So
        // when we reach this line, we know that response is not null.
        val idToken = tokenResponse!!.idToken!!
        val decodedJWT = JWT.decode(idToken)

        val keyId = decodedJWT.getHeaderClaim("kid").asString()
        val key = jwks?.find { it.id == keyId }

        if (key == null) {
          continuation.resumeWithException(Exception("Unknown key $keyId"))
          return@performTokenRequest
        }

        try {
          val algorithm = Algorithm.RSA256(key.publicKey as RSAPublicKey)
          val verifier =
            JWT
              .require(algorithm)
              .withIssuer(domain.toString())
              // Add five minutes of leeway when validating nbf and iat.
              .acceptLeeway(5.minutes.inWholeSeconds)
              .build()

          verifier.verify(idToken)
          continuation.resume(idToken)
        } catch (exception: JWTVerificationException) {
          continuation.resumeWithException(exception)
        }
      }
    }

  private fun validateState(
    request: AuthorizationManagementRequest,
    response: AuthorizationManagementResponse,
  ): Boolean {
    if (request.state != response.state) {
      Log.w(
        TAG,
        "State returned in authorization response (${response.state}) does not match state from request (${request.state}) - discarding response",
      )
      return false
    }
    return true
  }

  suspend fun logout(idToken: String?) {
    val endSessionRequest =
      EndSessionRequest
        .Builder(serviceConfiguration)
        .setIdTokenHint(idToken)
        .setPostLogoutRedirectUri(redirectUri)
        .build()

    val callbackUri = launchBrowser(endSessionRequest)

    val response =
      EndSessionResponse
        .Builder(endSessionRequest)
        .setState(
          callbackUri.getQueryParameter(
            "state",
          ),
        ).build()

    validateState(endSessionRequest, response)
  }

  /**
   * Starts the PAR flow, as described in https://datatracker.ietf.org/doc/html/rfc9126
   */
  private suspend fun pushAuthorizationRequest(authorizationRequest: AuthorizationRequest): Uri =
    withContext(
      Dispatchers.IO,
    ) {
      val response =
        httpClient.submitForm(
          serviceConfiguration.discoveryDoc!!
            .docJson
            .get(
              "pushed_authorization_request_endpoint",
            ).toString(),
        ) {
          // The FormDataContent class appends ; charset=UTF-8 to the content-type, which Verify does not like. So we create our own type
          setBody(
            object : OutgoingContent.ByteArrayContent() {
              override val contentType =
                ContentType.Application.FormUrlEncoded.withoutParameters()

              override fun bytes() =
                parametersOf(
                  authorizationRequest.toUri().queryParameterNames.associateWith {
                    listOf(authorizationRequest.toUri().getQueryParameter(it)!!)
                  },
                ).formUrlEncode().toByteArray()
            },
          )
        }

      @Serializable()
      data class ParResponse(
        val request_uri: String,
        val expires_in: Int,
      )
      val parsedResponse = response.body<ParResponse>()

      serviceConfiguration.authorizationEndpoint
        .buildUpon()
        .appendQueryParameter("client_id", clientID)
        .appendQueryParameter(
          "request_uri",
          parsedResponse.request_uri,
        ).build()
    }

  /**
   * The continuation that should be invoked when control returns from the browser to this library.
   */
  private var browserFlowContinuation: Continuation<Uri>? = null

  private suspend fun launchBrowser(
    request: AuthorizationManagementRequest,
    uri: Uri = request.toUri(),
  ) = suspendCoroutine { continuation ->
    browserFlowContinuation = continuation

    if (tabType == TabType.AuthTab) {
      // Open the Authorization URI in an Auth Tab if supported by chrome
      val authTabIntent = AuthTabIntent.Builder().build()

      // Auth tab will use the default browser, but we force it to use chrome.
      // In the future, other browser _could_ support the auth tab API (like they support custom tabs). But at the time of writing, only chrome supports it.
      authTabIntent.intent.`package` = Browsers.Chrome.PACKAGE_NAME
      authTabIntent.launch(
        authTabIntentLauncher,
        uri,
        redirectUri.host!!,
        redirectUri.path!!,
      )
    } else {
      // Fall back to a Custom Tab.
      customTabIntentLauncher.launch(Pair(request, uri))
    }
  }

  private suspend fun fetchCriiptoJWKS() =
    withContext(Dispatchers.IO) {
      jwks = UrlJwkProvider(domain.toString()).getAll()
    }

  private suspend fun fetchCriiptoOIDCConfiguration() =
    suspendCoroutine { continuation ->
      AuthorizationServiceConfiguration.fetchFromIssuer(
        domain,
      ) { _serviceConfiguration, ex ->
        if (ex != null) {
          Log.e(TAG, "Failed to fetch OIDC configuration", ex)
          continuation.resumeWithException(ex)
        }
        if (_serviceConfiguration != null) {
          Log.d(TAG, "Fetched OIDC configuration")
          serviceConfiguration = _serviceConfiguration
          continuation.resume(Unit)
        }
      }
    }
}
