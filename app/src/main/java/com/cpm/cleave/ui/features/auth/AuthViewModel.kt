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
    private val authRepository: IAuthRepository,
    initialRegisterMode: Boolean = false
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState(isRegisterMode = initialRegisterMode))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // --- Validation Helpers ---
    private fun isValidEmail(email: String): Boolean {
        return email.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[a-zA-Z]{2,}\$"))
    }

    private fun isValidName(name: String): Boolean {
        // Allows upper/lowercase letters, numbers, spaces, underscores, periods, and hyphens.
        // Rejects emojis, special symbols (!@#$%^&*), and invisible formatting characters.
        return name.matches(Regex("^[a-zA-Z0-9_ .-]+$"))
    }

    // --- State Updaters ---
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

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearResetPasswordMessage() {
        _uiState.update { it.copy(resetPasswordMessage = null) }
    }

    // --- Authentication Actions ---
    fun signInWithEmail() {
        val state = _uiState.value
        val email = state.email.trim()
        
        if (email.isBlank() || state.password.isBlank()) {
            setTransientError("Email and password are required")
            return
        }
        
        if (!isValidEmail(email)) {
            setTransientError("Please enter a valid email address")
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.signInWithEmail(
                email = email,
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
                setTransientError(error.localizedMessage ?: error.message ?: "Could not sign in")
            }
        }
    }

    fun signUpWithEmail() {
        val state = _uiState.value
        val name = state.name.trim()
        val email = state.email.trim()

        if (name.isBlank()) {
            setTransientError("Name is required")
            return
        }
        if (name.length > 30) {
            setTransientError("Name cannot exceed 30 characters")
            return
        }
        // Name Validation Check
        if (!isValidName(name)) {
            setTransientError("Name can only contain letters, numbers, spaces, dots, hyphens, and underscores")
            return
        }
        if (email.isBlank() || state.password.isBlank()) {
            setTransientError("Email and password are required")
            return
        }
        if (!isValidEmail(email)) {
            setTransientError("Please enter a valid email address")
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.signUpWithEmail(
                name = name,
                email = email,
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
                setTransientError(error.localizedMessage ?: error.message ?: "Could not sign up")
            }
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        if (idToken.isBlank()) {
            setTransientError("Invalid Google token")
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
                    setTransientError(error.localizedMessage ?: error.message ?: "Could not sign in with Google")
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
                    setTransientError(error.localizedMessage ?: error.message ?: "Could not create guest account")
                }
        }
    }

    fun sendPasswordResetEmail() {
        val email = _uiState.value.email.trim()
        if (email.isBlank()) {
            setTransientError("Please enter your email first")
            return
        }
        if (!isValidEmail(email)) {
            setTransientError("Please enter a valid email address")
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null, resetPasswordMessage = null) }

        viewModelScope.launch {
            authRepository.sendPasswordResetEmail(email)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            resetPasswordMessage = "Password reset email sent. Check your email."
                        )
                    }
                }
                .onFailure { error ->
                    setTransientError(error.localizedMessage ?: "Could not send reset email")
                }
        }
    }
}