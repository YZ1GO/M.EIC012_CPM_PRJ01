package com.cpm.cleave.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.data.repository.contracts.IExpenseRepository
import com.cpm.cleave.data.repository.contracts.IGroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddExpenseViewModel(
    private val groupRepository: IGroupRepository,
    private val expenseRepository: IExpenseRepository,
    private val groupId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

    init {
        loadGroupMembers()
    }

    private fun loadGroupMembers() {
        viewModelScope.launch {
            groupRepository.getGroupById(groupId)
                .onSuccess { group ->
                    val members = group?.members ?: emptyList()
                    _uiState.update {
                        it.copy(
                            availablePayers = members,
                            payerId = members.firstOrNull() ?: "",
                            selectedSplitMemberIds = members.toSet()
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Could not load group members")
                    }
                }
        }
    }

    fun onAmountChanged(value: String) {
        _uiState.update { it.copy(amountInput = value, errorMessage = null) }
    }

    fun onDescriptionChanged(value: String) {
        _uiState.update { it.copy(description = value, errorMessage = null) }
    }

    fun onPayerChanged(value: String) {
        _uiState.update { current ->
            current.copy(
                payerId = value,
                selectedSplitMemberIds = current.selectedSplitMemberIds + value,
                errorMessage = null
            )
        }
    }

    fun onSplitModeChanged(mode: SplitMode) {
        _uiState.update { current ->
            val selected = if (mode == SplitMode.ALL_MEMBERS) {
                current.availablePayers.toSet()
            } else {
                (current.selectedSplitMemberIds.ifEmpty { current.availablePayers.toSet() } + current.payerId)
            }
            current.copy(splitMode = mode, selectedSplitMemberIds = selected, errorMessage = null)
        }
    }

    fun onSplitMemberToggled(memberId: String, checked: Boolean) {
        _uiState.update { current ->
            if (memberId == current.payerId && !checked) {
                return@update current
            }
            val updatedSelection = current.selectedSplitMemberIds.toMutableSet().apply {
                if (checked) add(memberId) else remove(memberId)
            }
            current.copy(selectedSplitMemberIds = updatedSelection, errorMessage = null)
        }
    }

    fun createExpense(onSuccess: () -> Unit) {
        val state = _uiState.value
        val amount = state.amountInput.toDoubleOrNull()

        if (amount == null || amount <= 0.0) {
            _uiState.update { it.copy(errorMessage = "Enter a valid amount") }
            return
        }

        if (state.payerId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Select a payer") }
            return
        }

        val splitMemberIds = if (state.splitMode == SplitMode.ALL_MEMBERS) {
            state.availablePayers
        } else {
            (state.selectedSplitMemberIds + state.payerId).toList()
        }

        if (splitMemberIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one member to split with") }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            expenseRepository.createExpense(
                groupId = groupId,
                amount = amount,
                description = state.description,
                paidByUserId = state.payerId,
                splitMemberIds = splitMemberIds
            ).onSuccess {
                _uiState.update { it.copy(isLoading = false) }
                onSuccess()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not create expense"
                    )
                }
            }
        }
    }
}
