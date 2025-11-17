package eu.idura.verifyexample.ui

import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auth0.android.jwt.JWT
import eu.idura.verify.IduraVerify
import eu.idura.verify.eid.EID
import eu.idura.verifyexample.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class LoginState {
  class LoggedIn(
    val idToken: String,
    val name: String?,
    val sub: String,
    val identityscheme: String,
  ) : LoginState()

  class NotLoggedIn(
    var errorMessage: String? = null,
  ) : LoginState()

  class Loading : LoginState()
}

class LoginViewModel(
  initialState: LoginState,
  activity: ComponentActivity?,
) : ViewModel() {
  // The Idura verify instance cannot be instantiated until we have an activity. However, we don't have an activity in compose previews. In order to avoid null checks, we make it a lateinit property
  private lateinit var iduraVerify: IduraVerify
  private val _uiState = MutableStateFlow(initialState)
  val uiState: StateFlow<LoginState> = _uiState.asStateFlow()

  init {
    if (activity != null) {
      iduraVerify =
        IduraVerify(
          BuildConfig.IDURA_CLIENT_ID,
          "https://${BuildConfig.IDURA_DOMAIN}".toUri(),
          activity = activity,
        )
    }
  }

  fun login(eid: EID<*>) =
    viewModelScope.launch {
      _uiState.update { LoginState.Loading() }
      try {
        val idToken = iduraVerify.login(eid)
        val jwt = JWT(idToken)
        val nameClaim = jwt.getClaim("name")

        _uiState.update {
          LoginState.LoggedIn(
            idToken,
            nameClaim.asString(),
            jwt.getClaim("sub").asString()!!,
            jwt.getClaim("identityscheme").asString()!!,
          )
        }
      } catch (ex: Exception) {
        _uiState.update { LoginState.NotLoggedIn(ex.localizedMessage) }
      }
    }

  fun logout() =
    viewModelScope.launch {
      _uiState.update { LoginState.Loading() }
      try {
        iduraVerify.logout((_uiState.value as? LoginState.LoggedIn)?.idToken)
        _uiState.update { LoginState.NotLoggedIn() }
      } catch (ex: Exception) {
        _uiState.update { LoginState.NotLoggedIn(ex.localizedMessage) }
      }
    }
}
