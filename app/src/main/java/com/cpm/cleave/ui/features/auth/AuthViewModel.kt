package com.cpm.cleave.ui.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: IAuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(name = value, errorMessage = null) }
    }

    fun onEmailChanged(value: String) {
        _uiState.update { it.copy(email = value, errorMessage = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun setRegisterMode(isRegister: Boolean) {
        _uiState.update {
            it.copy(
                isRegisterMode = isRegister,
                errorMessage = null
            )
        }
    }

    fun setMergeGuestDataOnSignIn(merge: Boolean) {
        _uiState.update {
            it.copy(
                mergeGuestDataOnSignIn = merge,
                errorMessage = null
            )
        }
    }

    fun setTransientError(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = message
            )
        }
    }

    fun signInWithEmail() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Email and password are required") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.signInWithEmail(
                email = state.email.trim(),
                password = state.password,
                mergeAnonymousData = state.mergeGuestDataOnSignIn
            ).onSuccess {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = error.localizedMessage ?: error.message ?: error.toString()
                    )
                }
            }
        }
    }

    fun signUpWithEmail() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Name is required") }
            return
        }
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Email and password are required") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.signUpWithEmail(
                name = state.name.trim(),
                email = state.email.trim(),
                password = state.password,
                mergeAnonymousData = true
            ).onSuccess {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = error.localizedMessage ?: error.message ?: error.toString()
                    )
                }
            }
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        if (idToken.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Invalid Google token") }
            return
        }

        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.signInWithGoogleIdToken(
                idToken = idToken,
                mergeAnonymousData = if (state.isRegisterMode) true else state.mergeGuestDataOnSignIn
            )
                .onSuccess {
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            errorMessage = error.localizedMessage ?: error.message ?: error.toString()
                        )
                    }
                }
        }
    }

    fun continueAsGuest() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.getOrCreateAnonymousUser("Guest")
                .onSuccess {
                    // Wait a short moment to ensure session is established
                    kotlinx.coroutines.delay(250)
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            errorMessage = error.localizedMessage ?: error.message ?: error.toString()
                        )
                    }
                }
        }
    }
}
