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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.AuthorizationManagementRequest
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

  /**
   * The currently in-flight request - Either an authorization or an end session request.
   */
  private var currentRequest: AuthorizationManagementRequest? = null

  /**
   * The continuation that should be invoked when a login request completes
   */
  private var loginRequestContinuation: Continuation<String>? = null

  /**
   * The continuation that should be invoked when a logout request completes
   */
  private var logoutRequestContinuation: Continuation<Unit>? = null

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

    val enabledBrowsers =
      listOf(
        Browsers.Chrome.PACKAGE_NAME,
        Browsers.SBrowser.PACKAGE_NAME,
        BRAVE,
        EDGE,
      ).associateWith {
        (
          CustomTabsClient.getPackageName(
            activity,
            listOf(it),
            true,
          ) != null
        )
      }

    val browserMatcher: BrowserMatcher =
      when (tabType) {
        // When using an auth tab, we do not need the internal browser matching logic from appauth
        TabType.AuthTab -> {
          Log.i(TAG, "Using Chrome with auth tab")
          BrowserMatcher { false }
        }
        TabType.CustomTab ->
          if (enabledBrowsers[Browsers.Chrome.PACKAGE_NAME] == true) {
            Log.i(TAG, "Using Chrome with custom tab")
            VersionedBrowserMatcher.CHROME_CUSTOM_TAB
          } else if (enabledBrowsers[Browsers.SBrowser.PACKAGE_NAME] == true) {
            Log.i(TAG, "Using Samsung browser with custom tab")
            VersionedBrowserMatcher.SAMSUNG_CUSTOM_TAB
          } else if (enabledBrowsers[BRAVE] == true) {
            Log.i(TAG, "Using Brave with custom tab")
            BrowserMatcher { descriptor -> descriptor.packageName === BRAVE }
          } else if (enabledBrowsers[EDGE] == true) {
            Log.i(TAG, "Using Edge with custom tab")
            BrowserMatcher { descriptor -> descriptor.packageName === EDGE }
          } else {
            Log.i(TAG, "Falling back to any browser")
            // Fallback to any browser. TODO: maybe disable via flag?
            AnyBrowserMatcher.INSTANCE
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

  private fun handleResultUri(uri: Uri) {
    val currentRequest = this.currentRequest
    if (currentRequest == null) {
      Log.d(TAG, "Got a result URI $uri, but no active request.")
      return
    }

    val response =
      when (currentRequest) {
        is AuthorizationRequest ->
          AuthorizationResponse
            .Builder(currentRequest)
            .fromUri(uri)
            .build()

        is EndSessionRequest ->
          EndSessionResponse
            .Builder(currentRequest)
            .setState(
              uri.getQueryParameter(
                "state",
              ),
            ).build()

        else -> {
          Log.d(TAG, "Unsupported request type $currentRequest")
          return
        }
      }

    if (currentRequest.state != response.state) {
      Log.w(
        TAG,
        "State returned in authorization response (${response.state}) does not match state from request (${currentRequest.state}) - discarding response",
      )
      return
    }

    when (response) {
      is AuthorizationResponse -> {
        CoroutineScope(Dispatchers.IO).launch {
          authorizationService.performTokenRequest(
            response.createTokenExchangeRequest(),
          ) { response, ex ->
            if (ex != null) {
              loginRequestContinuation?.resumeWithException(ex)
              return@performTokenRequest
            }

            // From TokenResponseCallback - Exactly one of `response` or `ex` will be non-null. So
            // when we reach this line, we know that response is not null.
            val idToken = response!!.idToken!!
            val decodedJWT = JWT.decode(idToken)

            val keyId = decodedJWT.getHeaderClaim("kid").asString()
            val key = jwks?.find { it.id == keyId }

            if (key == null) {
              loginRequestContinuation?.resumeWithException(Exception("Unknown key $keyId"))
              return@performTokenRequest
            }

            try {
              val algorithm = Algorithm.RSA256(key.publicKey as RSAPublicKey)
              val verifier =
                JWT
                  .require(algorithm)
                  .withIssuer(domain.toString())
                  // Do not throw on JWTs with iat "in the future". This can easily happen due to clock skew, see https://github.com/auth0/java-jwt/issues/467
                  .ignoreIssuedAt()
                  .acceptNotBefore(5) // Add five seconds of leeway when validating nbf.
                  .build()

              verifier.verify(idToken)
              loginRequestContinuation?.resume(idToken)
            } catch (exception: JWTVerificationException) {
              loginRequestContinuation?.resumeWithException(exception)
            }
          }
        }
      }

      is EndSessionResponse -> logoutRequestContinuation?.resume(Unit)
    }

    this.currentRequest = null
  }

  private fun handleException(ex: Exception) {
    if (currentRequest is AuthorizationRequest) {
      loginRequestContinuation?.resumeWithException(ex)
    } else if (currentRequest is EndSessionRequest) {
      logoutRequestContinuation?.resumeWithException(ex)
    }
  }

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

    return suspendCoroutine { continuation ->
      loginRequestContinuation = continuation
      launchBrowser(authorizationRequest, parRequestUri)
    }
  }

  suspend fun logout(idToken: String?) =
    suspendCoroutine { continuation ->
      logoutRequestContinuation = continuation

      launchBrowser(
        EndSessionRequest
          .Builder(serviceConfiguration)
          .setIdTokenHint(idToken)
          .setPostLogoutRedirectUri(redirectUri)
          .build(),
      )
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

  private fun launchBrowser(
    request: AuthorizationManagementRequest,
    uri: Uri = request.toUri(),
  ) {
    this.currentRequest = request

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
