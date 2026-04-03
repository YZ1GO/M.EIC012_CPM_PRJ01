package com.cpm.cleave.ui.features.joingroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import com.cpm.cleave.domain.usecase.RequestJoinGroupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class JoinGroupViewModel(
    private val requestJoinGroupUseCase: RequestJoinGroupUseCase,
    private val scannerRepository: IScannerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(JoinGroupUiState())
    val uiState: StateFlow<JoinGroupUiState> = _uiState.asStateFlow()

    fun onJoinCodeChanged(newCode: String) {
        val normalized = newCode
            .filter { it.isLetterOrDigit() }
            .uppercase(Locale.ROOT)
            .take(8)
            
        _uiState.update { it.copy(joinCode = normalized, errorMessage = null) }
    }

    fun applyDeepLinkJoinCode(joinCode: String?) {
        val candidate = joinCode
            ?.filterNot { it.isWhitespace() }
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return

        _uiState.update {
            if (it.joinCode == candidate) it else it.copy(joinCode = candidate, errorMessage = null)
        }
    }

    fun openScanner() {
        _uiState.update { it.copy(isScannerVisible = true, errorMessage = null) }
    }

    fun closeScanner() {
        _uiState.update { it.copy(isScannerVisible = false) }
    }

    fun onQrCodeScanned(rawValue: String, onSuccess: () -> Unit) {
        val joinCode = scannerRepository.extractJoinCode(rawValue)
        if (joinCode.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    isScannerVisible = false,
                    errorMessage = "Could not read a valid join code from QR"
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                joinCode = joinCode,
                isScannerVisible = false,
                errorMessage = null
            )
        }

        joinGroup(onSuccess)
    }

    fun joinGroup(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.isLoading) return

        if (state.joinCode.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a join code") }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            val result = requestJoinGroupUseCase.execute(state.joinCode)
            if (result.isSuccess) {
                _uiState.update { it.copy(isLoading = false, successMessage = "Joined group successfully!") }
                onSuccess()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Could not join group"
                    )
                }
            }
        }
    }
}
