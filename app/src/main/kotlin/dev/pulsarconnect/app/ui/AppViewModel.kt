package dev.pulsarconnect.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pulsarconnect.app.AppContainer
import dev.pulsarconnect.core.model.Credentials
import dev.pulsarconnect.core.model.Vin
import dev.pulsarconnect.core.network.BajajApi
import dev.pulsarconnect.core.network.BajajSessionManager
import dev.pulsarconnect.core.network.SignInResult
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(
    private val sessionManager: BajajSessionManager,
    private val api: BajajApi,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state = _state.asStateFlow()

    init {
        restoreSession()
    }

    /**
     * The whole point of this app: a stored session means the login screen is
     * never shown again, and this path does not touch the network.
     */
    private fun restoreSession() {
        viewModelScope.launch {
            val stored = sessionManager.restore()
            if (stored == null) {
                _state.value = UiState.SignedOut()
                return@launch
            }

            _state.value = UiState.SignedIn(
                vin = stored.credentials.vin.value,
                bikeName = null,
            )
            loadBike(stored.credentials.vin.value)
        }
    }

    fun signIn(vinInput: String, accessCode: String) {
        val credentials = runCatching {
            Credentials(vin = Vin.of(vinInput), accessCode = accessCode.trim())
        }.getOrElse { error ->
            _state.value = UiState.SignedOut(error = error.message ?: "Check your VIN and access code")
            return
        }

        viewModelScope.launch {
            _state.value = UiState.SignedOut(busy = true)

            val result = runCatching { sessionManager.signIn(credentials) }
                .getOrElse { error ->
                    _state.value = UiState.SignedOut(error = error.toUserMessage())
                    return@launch
                }

            when (result) {
                is SignInResult.Success -> {
                    _state.value = UiState.SignedIn(
                        vin = credentials.vin.value,
                        bikeName = null,
                    )
                    loadBike(credentials.vin.value)
                }

                SignInResult.VehicleNotRegistered ->
                    _state.value = UiState.SignedOut(
                        error = "This VIN is not registered. Create the account in the official " +
                            "Bajaj Ride Connect app first, then log in here.",
                    )

                is SignInResult.Rejected ->
                    _state.value = UiState.SignedOut(error = result.message)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            sessionManager.signOut()
            _state.value = UiState.SignedOut()
        }
    }

    /**
     * Best effort: the home screen is useful offline, so a failure here leaves
     * the VIN visible instead of blocking the UI.
     */
    private fun loadBike(vin: String) {
        viewModelScope.launch {
            runCatching {
                sessionManager.withSession { session ->
                    api.vehicles(session.session.accessToken)
                }
            }.onSuccess { bikes ->
                val match = bikes.firstOrNull { it.chassisNumber.equals(vin, ignoreCase = true) }
                    ?: bikes.firstOrNull()
                val name = match?.nickname?.takeIf { it.isNotBlank() } ?: match?.model

                val current = _state.value
                if (name != null && current is UiState.SignedIn && current.vin == vin) {
                    _state.value = current.copy(bikeName = name)
                }
            }
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is IOException -> "Could not reach Bajaj. Check your connection and try again."
        else -> message ?: "Something went wrong."
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(container.sessionManager, container.api) as T
            }
    }
}

sealed interface UiState {
    data object Loading : UiState

    data class SignedOut(
        val error: String? = null,
        val busy: Boolean = false,
    ) : UiState

    data class SignedIn(
        val vin: String,
        val bikeName: String?,
    ) : UiState
}
