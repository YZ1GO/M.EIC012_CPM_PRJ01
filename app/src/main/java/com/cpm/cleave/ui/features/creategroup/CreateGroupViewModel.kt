package com.cpm.cleave.ui.features.creategroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.usecase.RequestCreateGroupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateGroupViewModel (
    private val requestCreateGroupUseCase: RequestCreateGroupUseCase
) : ViewModel() {

    // The internal state that can be changed
    private val _uiState = MutableStateFlow(CreateGroupUiState())

    // The public state the UI reads (read-only)
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

    fun onNameChanged(newName: String) {
        _uiState.update { it.copy(Name = newName, errorMessage = null) }
    }

    fun onCurrencyChanged(newCurrency: String) {
        _uiState.update { it.copy(Currency = newCurrency, errorMessage = null) }
    }

    fun createGroup(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.Name.isBlank()) return

        viewModelScope.launch {
            val result = requestCreateGroupUseCase.execute(state.Name, state.Currency)
            if (result.isSuccess) {
                onSuccess()
            } else {
                _uiState.update {
                    it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Could not create group")
                }
            }
        }
    }
}