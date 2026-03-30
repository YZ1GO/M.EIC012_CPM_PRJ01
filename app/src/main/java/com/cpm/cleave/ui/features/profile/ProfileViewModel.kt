package com.cpm.cleave.ui.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val repository: IAuthRepository
) : ViewModel() {

    private val limits = repository.getAnonymousLimits()

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            isLoading = true,
            maxGroups = limits.maxGroups
        )
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<ProfileUiEffect>()
    val uiEffect: SharedFlow<ProfileUiEffect> = _uiEffect.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null
                )
            }

            repository.getCurrentUser()
                .onSuccess { user ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentUser = user,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentUser = null,
                            errorMessage = error.message ?: "Could not load profile"
                        )
                    }
                }
        }
    }

    fun onLogInClicked() {
        viewModelScope.launch {
            _uiEffect.emit(ProfileUiEffect.NavigateToSignIn)
        }
    }

    fun onRegisterClicked() {
        viewModelScope.launch {
            _uiEffect.emit(ProfileUiEffect.NavigateToRegister)
        }
    }

    fun onSignOutClicked() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            repository.signOut()
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            currentUser = null,
                            errorMessage = null
                        )
                    }
                    _uiEffect.emit(ProfileUiEffect.SignedOut)
                }
                .onFailure { error ->
                    val message = error.message ?: "Could not sign out"
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = message
                        )
                    }
                    _uiEffect.emit(ProfileUiEffect.ShowMessage(message))
                }
        }
    }

    // TODO: delete
    fun onSwitchDebugUserClicked() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            repository.switchDebugAnonymousUser()
                .onSuccess { user ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            currentUser = user,
                            errorMessage = null
                        )
                    }
                    _uiEffect.emit(ProfileUiEffect.ShowMessage("Switched debug user"))
                    // TODO(debug-cleanup): remove this emit when deleting debug switch-user feature.
                    _uiEffect.emit(ProfileUiEffect.DebugUserSwitched)
                }
                .onFailure { error ->
                    val message = error.message ?: "Could not switch debug user"
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = message
                        )
                    }
                    _uiEffect.emit(ProfileUiEffect.ShowMessage(message))
                }
        }
    }

    // TODO: delete
    fun onClearDebugDataClicked() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            repository.clearDebugDatabase()
                .onSuccess {
                    repository.switchDebugAnonymousUser()
                        .onSuccess { user ->
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    currentUser = user,
                                    errorMessage = null
                                )
                            }
                            _uiEffect.emit(ProfileUiEffect.ShowMessage("Cleared local debug data"))
                            // TODO(debug-cleanup): remove this emit when deleting debug clear-data feature.
                            _uiEffect.emit(ProfileUiEffect.DebugDataCleared)
                        }
                        .onFailure { error ->
                            val message = error.message
                                ?: "Cleared DB, but could not initialize debug user"
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    errorMessage = message
                                )
                            }
                            _uiEffect.emit(ProfileUiEffect.ShowMessage(message))
                        }
                }
                .onFailure { error ->
                    val message = error.message ?: "Could not clear local database"
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = message
                        )
                    }
                    _uiEffect.emit(ProfileUiEffect.ShowMessage(message))
                }
        }
    }
}
