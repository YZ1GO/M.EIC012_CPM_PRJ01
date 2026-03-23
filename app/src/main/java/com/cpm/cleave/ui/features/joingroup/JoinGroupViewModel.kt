package com.cpm.cleave.ui.features.joingroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.usecase.RequestJoinGroupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class JoinGroupViewModel(
    private val requestJoinGroupUseCase: RequestJoinGroupUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(JoinGroupUiState())
    val uiState: StateFlow<JoinGroupUiState> = _uiState.asStateFlow()

    fun onJoinCodeChanged(newCode: String) {
        val normalized = newCode
            .filterNot { it.isWhitespace() }
            .uppercase(Locale.ROOT)
        _uiState.update { it.copy(joinCode = normalized, errorMessage = null) }
    }

    fun joinGroup(onSuccess: () -> Unit) {
        val state = _uiState.value
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
