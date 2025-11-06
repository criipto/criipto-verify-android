package com.criipto.verify

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
import androidx.lifecycle.lifecycleScope
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import android.content.Context as AndroidContext
import io.opentelemetry.context.Context as OtelContext

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

class CriiptoVerify(
  private val clientID: String,
  private val domain: Uri,
  private val redirectUri: Uri = "$domain/android/callback".toUri(),
  private val appSwitchUri: Uri = "$domain/android/callback/appswitch".toUri(),
  private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
  private val httpClient =
    HttpClient(Android) {
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
   * The type of browser tab to user, either custom tab or auth tab.
   * Determining the supported browser requires an activity, so this is set in `onCreate`.
   */
  private lateinit var tabType: TabType

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

  private val tracing = Tracing(domain.host!!, httpClient)
  private val tracer =
    tracing.getTracer(BuildConfig.LIBRARY_PACKAGE_NAME, BuildConfig.VERSION)

  private lateinit var browserDescription: String
  private val getCriiptoJWKS = cacheResult(activity.lifecycleScope, this::loadCriiptoJWKS)
  private val getCriiptoOIDCConfiguration =
    cacheResult(activity.lifecycleScope, this::loadCriiptoOIDCConfiguration)

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
            context: AndroidContext,
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

    // Load the OIDC config and JWKS configuration, so it is ready when the user initiates a login
    activity.lifecycleScope.launch {
      async { runCatching { getCriiptoOIDCConfiguration() } }
      async { runCatching { getCriiptoJWKS() } }
    }
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
          browserDescription =
            "${Browsers.Chrome.PACKAGE_NAME} ${activity.packageManager.getPackageInfo(
              Browsers.Chrome.PACKAGE_NAME,
              0,
            ).versionName}, Auth tab"
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

          browserDescription =
            "$browserName ${activity.packageManager.getPackageInfo(
              browserName,
              0,
            ).versionName}, Custom tab"
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
    tracing.close()
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

  suspend fun login(eid: EID<*>): String =
    tracer
      .spanBuilder(
        "android sdk login",
      ).setAttribute("acr_value", eid.acrValue)
      .startAndRunSuspend {
        println("Login ${Span.current().spanContext}")
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
              getCriiptoOIDCConfiguration(),
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

        exchangeCode(authorizationRequest, callbackUri)
      }

  private suspend fun exchangeCode(
    request: AuthorizationRequest,
    callbackUri: Uri,
  ): String {
    val tokenResponse =
      tracer.spanBuilder("code exchange").startAndRunSuspend {
        val response =
          AuthorizationResponse
            .Builder(request)
            .fromUri(callbackUri)
            .build()

        if (!validateState(request, response)) {
          throw Exception("State mismatch")
        }

        suspendCoroutine { continuation ->
          authorizationService.performTokenRequest(
            response.createTokenExchangeRequest(),
          ) { tokenResponse, ex ->
            if (ex != null) {
              continuation.resumeWithException(ex)
            } else {
              // From TokenResponseCallback - Exactly one of `response` or `ex` will be non-null. So
              // when we reach this line, we know that response is not null.
              continuation.resume(tokenResponse!!)
            }
          }
        }
      }

    return tracer.spanBuilder("JWT verification").startAndRun {
      val idToken = tokenResponse.idToken!!
      val decodedJWT = JWT.decode(idToken)

      val keyId = decodedJWT.getHeaderClaim("kid").asString()
      val key = getCriiptoJWKS().find { it.id == keyId }

      if (key == null) {
        throw Exception("Unknown key $keyId")
      }

      val algorithm = Algorithm.RSA256(key.publicKey as RSAPublicKey)
      val verifier =
        JWT
          .require(algorithm)
          .withIssuer(domain.toString())
          // Add five minutes of leeway when validating nbf and iat.
          .acceptLeeway(5.minutes.inWholeSeconds)
          .build()

      verifier.verify(idToken)
      return@startAndRun idToken
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
        .Builder(
          getCriiptoOIDCConfiguration(),
        ).setIdTokenHint(idToken)
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
  private suspend fun pushAuthorizationRequest(authorizationRequest: AuthorizationRequest): Uri {
    val response =
      httpClient.submitForm(
        getCriiptoOIDCConfiguration()
          .discoveryDoc!!
          .docJson
          .get(
            "pushed_authorization_request_endpoint",
          ).toString(),
      ) {
        tracing.propagators().textMapPropagator.inject(
          OtelContext.current(),
          this,
          KtorRequestSetter,
        )

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

    if (response.status.value != 201) {
      throw Error(
        "Error during PAR request ${response.status.value} ${response.status.description}",
      )
    }

    @Serializable()
    data class ParResponse(
      val request_uri: String,
      val expires_in: Int,
    )
    val parsedResponse = response.body<ParResponse>()

    return getCriiptoOIDCConfiguration()
      .authorizationEndpoint
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
  ): Uri =
    tracer
      .spanBuilder("launch browser")
      .setAttribute("browser", browserDescription)
      .startAndRunSuspend {
        suspendCoroutine { continuation ->
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
      }

  private suspend fun loadCriiptoJWKS() =
    withContext(Dispatchers.IO) {
      UrlJwkProvider(
        domain.toString(),
      ).all
    }

  private suspend fun loadCriiptoOIDCConfiguration(): AuthorizationServiceConfiguration =
    suspendCoroutine { continuation ->
      AuthorizationServiceConfiguration.fetchFromIssuer(
        domain,
      ) { serviceConfiguration, ex ->
        if (ex != null) {
          Log.e(TAG, "Failed to fetch OIDC configuration", ex)
          continuation.resumeWithException(ex)
        } else {
          Log.d(TAG, "Fetched OIDC configuration")
          continuation.resume(serviceConfiguration!!)
        }
      }
    }
}

internal fun <T> cacheResult(
  scope: CoroutineScope,
  load: suspend () -> T,
): suspend () -> T {
  var cachedDeferred: Deferred<T>? = null
  return {
    // If there is currently no cached deferred, or if the current cached deferred has failed, create a new one
    if (cachedDeferred == null || cachedDeferred?.isCancelled == true) {
      cachedDeferred =
        scope.async {
          load()
        }
    }

    cachedDeferred.await()
  }
}
