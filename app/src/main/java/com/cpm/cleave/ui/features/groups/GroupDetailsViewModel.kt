package com.cpm.cleave.ui.features.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.usecase.GetGroupDetailsUseCase
import com.cpm.cleave.domain.usecase.RequestDeleteExpenseUseCase
import com.cpm.cleave.domain.usecase.RequestDeleteGroupUseCase
import com.cpm.cleave.domain.usecase.RequestExpelGroupMemberUseCase
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GroupDetailsViewModel(
    private val groupId: String,
    private val getGroupDetailsUseCase: GetGroupDetailsUseCase,
    private val requestDeleteExpenseUseCase: RequestDeleteExpenseUseCase,
    private val requestDeleteGroupUseCase: RequestDeleteGroupUseCase,
    private val requestExpelGroupMemberUseCase: RequestExpelGroupMemberUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupDetailsUiState())
    val uiState: StateFlow<GroupDetailsUiState> = _uiState.asStateFlow()

    init {
        observeGroupData()
    }

    private fun observeGroupData() {
        viewModelScope.launch {
            getGroupDetailsUseCase.observe(groupId)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Could not load group details"
                        )
                    }
                }
                .collect { result ->
                    result
                        .onSuccess { data ->
                            _uiState.update {
                                val preservedError = if (it.selectedMemberForExpulsionId != null) {
                                    it.errorMessage
                                } else {
                                    null
                                }
                                it.copy(
                                    isLoading = false,
                                    group = data.group,
                                    expenses = data.expenses,
                                    debts = data.debts,
                                    debtsWithReason = data.debtsWithReason,
                                    userDisplayNames = data.userDisplayNames,
                                    userPhotoUrls = data.userPhotoUrls,
                                    canDeleteGroup = !data.group.ownerId.isNullOrBlank() && data.group.ownerId == data.currentUserId,
                                    errorMessage = preservedError
                                )
                            }
                        }
                        .onFailure { error ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = error.message ?: "Could not load group details"
                                )
                            }
                        }
                }
        }
    }

    fun refreshGroupData() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            getGroupDetailsUseCase.execute(groupId)
                .onSuccess { data ->
                    _uiState.update {
                        val preservedError = if (it.selectedMemberForExpulsionId != null) {
                            it.errorMessage
                        } else {
                            null
                        }
                        it.copy(
                            isLoading = false,
                            group = data.group,
                            expenses = data.expenses,
                            debts = data.debts,
                            debtsWithReason = data.debtsWithReason,
                            userDisplayNames = data.userDisplayNames,
                            userPhotoUrls = data.userPhotoUrls,
                            canDeleteGroup = !data.group.ownerId.isNullOrBlank() && data.group.ownerId == data.currentUserId,
                            errorMessage = preservedError
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Could not load group details"
                        )
                    }
                }
        }
    }

    fun onDeleteGroupClicked(onSuccess: () -> Unit) {
        if (_uiState.value.isDeleting || !_uiState.value.canDeleteGroup) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }

            requestDeleteGroupUseCase.execute(groupId)
                .onSuccess {
                    _uiState.update { it.copy(isDeleting = false) }
                    onSuccess()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = error.message ?: "Could not delete group"
                        )
                    }
                }
        }
    }

    fun onMemberLongPressed(memberId: String) {
        val currentState = _uiState.value
        val ownerId = currentState.group?.ownerId

        if (!currentState.canDeleteGroup) return
        if (ownerId.isNullOrBlank()) return
        if (memberId.isBlank()) return
        if (memberId == ownerId) return

        _uiState.update {
            it.copy(
                selectedMemberForExpulsionId = memberId,
                errorMessage = null
            )
        }
    }

    fun onExpenseLongPressed(expenseId: String) {
        val currentState = _uiState.value
        if (!currentState.canDeleteGroup) return
        if (currentState.isDeletingExpense) return
        if (expenseId.isBlank()) return

        _uiState.update {
            it.copy(
                selectedExpenseForDeletionId = expenseId,
                errorMessage = null
            )
        }
    }

    fun dismissExpenseDeletionDialog() {
        if (_uiState.value.isDeletingExpense) return
        _uiState.update { it.copy(selectedExpenseForDeletionId = null) }
    }

    fun confirmExpenseDeletion() {
        val state = _uiState.value
        val expenseId = state.selectedExpenseForDeletionId ?: return

        if (state.isDeletingExpense || !state.canDeleteGroup) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingExpense = true, errorMessage = null) }

            requestDeleteExpenseUseCase.execute(groupId = groupId, expenseId = expenseId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDeletingExpense = false,
                            selectedExpenseForDeletionId = null
                        )
                    }
                    refreshGroupData()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDeletingExpense = false,
                            errorMessage = error.message ?: "Could not delete expense"
                        )
                    }
                }
        }
    }

    fun dismissMemberExpulsionDialog() {
        if (_uiState.value.isExpellingMember) return
        _uiState.update { it.copy(selectedMemberForExpulsionId = null) }
    }

    fun confirmMemberExpulsion() {
        val state = _uiState.value
        val memberId = state.selectedMemberForExpulsionId ?: return

        if (state.isExpellingMember || !state.canDeleteGroup) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExpellingMember = true, errorMessage = null) }

            requestExpelGroupMemberUseCase.execute(groupId = groupId, memberId = memberId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isExpellingMember = false,
                            selectedMemberForExpulsionId = null
                        )
                    }
                    refreshGroupData()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isExpellingMember = false,
                            errorMessage = error.message ?: "Could not remove member"
                        )
                    }
                }
        }
    }
}
