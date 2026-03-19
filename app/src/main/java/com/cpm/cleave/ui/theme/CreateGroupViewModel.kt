package com.cpm.cleave.ui.theme

import androidx.lifecycle.ViewModel
import com.cpm.cleave.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CreateGroupViewModel (
    private val repository: Repository
) : ViewModel() {

    // The internal state that can be changed
    private val _uiState = MutableStateFlow(CreateGroupUiState())

    // The public state the UI reads (read-only)
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

    fun onNameChanged(newName: String) {
        _uiState.update { it.copy(Name = newName) }
    }

    fun onCurrencyChanged(newCurrency: String) {
        _uiState.update { it.copy(Currency = newCurrency) }
    }

    fun createGroup(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.Name.isBlank()) return

        val result = repository.createGroup(state.Name, state.Currency)
        if (result.isSuccess) {
            onSuccess()
        }
    }
}