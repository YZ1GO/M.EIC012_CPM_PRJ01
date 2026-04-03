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

    private var selectedPhotoBytes: ByteArray? = null
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

    private fun isValidName(name: String): Boolean {
        return name.matches(Regex("^[a-zA-Z0-9_ .-]+$"))
    }

    init {
        refresh()
    }

    fun refresh(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(errorMessage = null) }
            }

            repository.getCurrentUser()
                .onSuccess { user ->
                    val canResetPassword = if (user == null) {
                        false
                    } else {
                        repository.canResetPasswordForCurrentUser().getOrDefault(false)
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentUser = user,
                            errorMessage = null,
                            canResetPassword = canResetPassword
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentUser = null,
                            errorMessage = error.message ?: "Could not load profile",
                            canResetPassword = false
                        )
                    }
                }
        }
    }

    fun onLogInClicked() {
        viewModelScope.launch { _uiEffect.emit(ProfileUiEffect.NavigateToSignIn) }
    }

    fun onRegisterClicked() {
        viewModelScope.launch { _uiEffect.emit(ProfileUiEffect.NavigateToRegister) }
    }

    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    fun onProfilePhotoSelected(uri: String?, imageBytes: ByteArray?) {
        selectedPhotoBytes = imageBytes
        _uiState.update { it.copy(pendingPhotoUri = uri, errorMessage = null, successMessage = null) }
    }

    fun onSaveProfilePhotoClicked() {
        val isDeleting = _uiState.value.pendingPhotoUri == ""
        val imageBytes = selectedPhotoBytes

        if (!isDeleting && imageBytes == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingPhoto = true, errorMessage = null, successMessage = null) }

            val result = if (isDeleting) {
                repository.removeProfilePicture()
            } else {
                repository.updateProfilePhoto(imageBytes!!)
            }

            result
                .onSuccess { updatedUser ->
                    selectedPhotoBytes = null
                    _uiState.update {
                        it.copy(
                            isUploadingPhoto = false,
                            pendingPhotoUri = null,
                            currentUser = updatedUser,
                            errorMessage = null,
                            successMessage = if (isDeleting) "Profile photo removed" else "Profile photo updated"
                        )
                    }
                }
                .onFailure { error ->
                    val message = error.message ?: "Could not update profile photo"
                    _uiState.update {
                        it.copy(
                            isUploadingPhoto = false,
                            errorMessage = message,
                            successMessage = null
                        )
                    }
                }
        }
    }

    fun updateName(newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName == _uiState.value.currentUser?.name) return

        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Name is required", successMessage = null) }
            return
        }

        if (trimmedName.length > 30) {
            _uiState.update { it.copy(errorMessage = "Name cannot exceed 30 characters", successMessage = null) }
            return
        }
        
        if (!isValidName(trimmedName)) {
            _uiState.update {
                it.copy(
                    errorMessage = "Name can only contain letters, numbers, spaces, dots, hyphens, and underscores",
                    successMessage = null
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, successMessage = null) }
            repository.updateProfileName(trimmedName)
                .onSuccess { updatedUser ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            currentUser = updatedUser,
                            errorMessage = null,
                            successMessage = "Name updated successfully"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Failed to update name",
                            successMessage = null
                        )
                    }
                }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String) {
        if (!_uiState.value.canResetPassword) {
            _uiState.update {
                it.copy(
                    errorMessage = "Password change is only available for email/password accounts",
                    successMessage = null
                )
            }
            return
        }

        val current = currentPassword.trim()
        val next = newPassword.trim()
        val confirm = confirmPassword.trim()

        if (current.isBlank() || next.isBlank() || confirm.isBlank()) {
            _uiState.update { it.copy(errorMessage = "All password fields are required", successMessage = null) }
            return
        }

        if (next.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters", successMessage = null) }
            return
        }

        if (next != confirm) {
            _uiState.update { it.copy(errorMessage = "New passwords do not match", successMessage = null) }
            return
        }

        if (current == next) {
            _uiState.update {
                it.copy(
                    errorMessage = "New password must be different from current password",
                    successMessage = null
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, successMessage = null) }
            repository.changePassword(currentPassword = current, newPassword = next)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = null,
                            successMessage = "Password changed successfully"
                        )
                    }
                    _uiEffect.emit(ProfileUiEffect.PasswordChanged)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Could not change password",
                            successMessage = null
                        )
                    }
                }
        }
    }

    fun onSignOutClicked() {
        if (_uiState.value.isUploadingPhoto) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, successMessage = null) }
            repository.signOut()
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            currentUser = null,
                            errorMessage = null,
                            successMessage = null
                        )
                    }
                    _uiEffect.emit(ProfileUiEffect.SignedOut)
                }
                .onFailure { error ->
                    val message = error.message ?: "Could not sign out"
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = message,
                            successMessage = null
                        )
                    }
                }
        }
    }
}