package com.cpm.cleave.ui.features.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.usecase.GetGroupDetailsUseCase
import com.cpm.cleave.domain.usecase.RequestDeleteExpenseUseCase
import com.cpm.cleave.domain.usecase.RequestDeleteGroupUseCase
import com.cpm.cleave.domain.usecase.RequestExpelGroupMemberUseCase
import com.cpm.cleave.domain.usecase.RequestSettleDebtUseCase
import com.cpm.cleave.domain.usecase.RequestUpdateGroupUseCase
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Group
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GroupDetailsViewModel(
    private val groupId: String,
    private val getGroupDetailsUseCase: GetGroupDetailsUseCase,
    private val requestSettleDebtUseCase: RequestSettleDebtUseCase,
    private val requestDeleteExpenseUseCase: RequestDeleteExpenseUseCase,
    private val requestDeleteGroupUseCase: RequestDeleteGroupUseCase,
    private val requestExpelGroupMemberUseCase: RequestExpelGroupMemberUseCase,
    private val requestUpdateGroupUseCase: RequestUpdateGroupUseCase
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
                                val preservedError = if (
                                    it.selectedMemberForProfileId != null ||
                                    it.selectedMemberForExpulsionId != null ||
                                    it.selectedExpenseForDeletionId != null ||
                                    it.selectedDebtForPayment != null
                                ) {
                                    it.errorMessage
                                } else {
                                    null
                                }
                                it.copy(
                                    isLoading = false,
                                    currentUserId = data.currentUserId,
                                    group = data.group,
                                    expenses = data.expenses,
                                    debts = data.debts,
                                    debtsWithReason = data.debtsWithReason,
                                    userDisplayNames = data.userDisplayNames,
                                    userPhotoUrls = data.userPhotoUrls,
                                    userLastSeen = data.userLastSeen,
                                    canDeleteGroup = !data.group.ownerId.isNullOrBlank() && data.group.ownerId == data.currentUserId,
                                    editedGroupName = if (it.isEditingGroup) it.editedGroupName else data.group.name,
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
                        val preservedError = if (
                            it.selectedMemberForProfileId != null ||
                            it.selectedMemberForExpulsionId != null ||
                            it.selectedExpenseForDeletionId != null ||
                            it.selectedDebtForPayment != null
                        ) {
                            it.errorMessage
                        } else {
                            null
                        }
                        it.copy(
                            isLoading = false,
                            currentUserId = data.currentUserId,
                            group = data.group,
                            expenses = data.expenses,
                            debts = data.debts,
                            debtsWithReason = data.debtsWithReason,
                            userDisplayNames = data.userDisplayNames,
                            userPhotoUrls = data.userPhotoUrls,
                            userLastSeen = data.userLastSeen,
                            canDeleteGroup = !data.group.ownerId.isNullOrBlank() && data.group.ownerId == data.currentUserId,
                            editedGroupName = if (it.isEditingGroup) it.editedGroupName else data.group.name,
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

    fun onDebtClicked(debt: Debt) {
        val state = _uiState.value
        if (state.isSettlingDebt) return
        if (state.currentUserId.isNullOrBlank()) return
        if (state.currentUserId != debt.fromUser) return

        _uiState.update {
            it.copy(
                selectedDebtForPayment = debt,
                debtPaymentAmountInput = "${debt.amount}",
                errorMessage = null
            )
        }
    }

    fun onDebtPaymentAmountChanged(value: String) {
        val normalized = value
            .replace(',', '.')
            .filter { character -> character.isDigit() || character == '.' }
        _uiState.update { it.copy(debtPaymentAmountInput = normalized) }
    }

    fun dismissDebtPaymentDialog() {
        if (_uiState.value.isSettlingDebt) return
        _uiState.update {
            it.copy(
                selectedDebtForPayment = null,
                debtPaymentAmountInput = "",
                errorMessage = null
            )
        }
    }

    fun confirmDebtPayment() {
        val state = _uiState.value
        val selectedDebt = state.selectedDebtForPayment ?: return
        if (state.isSettlingDebt) return

        val amount = state.debtPaymentAmountInput
            .replace(',', '.')
            .toDoubleOrNull()

        if (amount == null || amount <= 0.0) {
            _uiState.update { it.copy(errorMessage = "Enter a valid amount greater than zero.") }
            return
        }

        if (amount > selectedDebt.amount) {
            _uiState.update { it.copy(errorMessage = "Amount cannot exceed current debt.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSettlingDebt = true, errorMessage = null) }

            requestSettleDebtUseCase.execute(
                groupId = groupId,
                debt = selectedDebt,
                amountToPay = amount
            )
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isSettlingDebt = false,
                            selectedDebtForPayment = null,
                            debtPaymentAmountInput = ""
                        )
                    }
                    refreshGroupData()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSettlingDebt = false,
                            errorMessage = error.message ?: "Could not register payment"
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

    fun onEditGroupClicked() {
        val group = _uiState.value.group ?: return
        if (!_uiState.value.canDeleteGroup) return

        _uiState.update {
            it.copy(
                isEditingGroup = true,
                editedGroupName = group.name,
                errorMessage = null
            )
        }
    }

    fun onEditedGroupNameChanged(value: String) {
        _uiState.update { it.copy(editedGroupName = value, errorMessage = null) }
    }

    fun dismissGroupEditDialog() {
        if (_uiState.value.isUpdatingGroup) return
        _uiState.update { it.copy(isEditingGroup = false) }
    }

    fun confirmGroupEdit(newImageBytes: ByteArray?, onSuccess: () -> Unit) {
        val state = _uiState.value
        val currentGroup = state.group ?: return
        if (!state.canDeleteGroup || state.isUpdatingGroup) return

        val trimmedName = state.editedGroupName.trim()
        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Group name is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingGroup = true, errorMessage = null) }

            val uploadedImageUrl = if (newImageBytes != null) {
                requestUpdateGroupUseCase.uploadGroupImage(newImageBytes)
                    .getOrElse { error ->
                        _uiState.update {
                            it.copy(
                                isUpdatingGroup = false,
                                errorMessage = error.message ?: "Could not upload group image"
                            )
                        }
                        return@launch
                    }
            } else {
                currentGroup.imageUrl
            }

            val updatedGroup = currentGroup.copy(
                name = trimmedName,
                imageUrl = uploadedImageUrl
            )

            requestUpdateGroupUseCase.execute(updatedGroup)
                .onSuccess { savedGroup ->
                    _uiState.update {
                        it.copy(
                            isUpdatingGroup = false,
                            isEditingGroup = false,
                            editedGroupName = savedGroup.name
                        )
                    }
                    onSuccess()
                    refreshGroupData()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isUpdatingGroup = false,
                            errorMessage = error.message ?: "Could not update group"
                        )
                    }
                }
        }
    }

    fun onRemoveMemberClicked(memberId: String) {
        val currentState = _uiState.value
        val ownerId = currentState.group?.ownerId

        if (!currentState.canDeleteGroup) return
        if (ownerId.isNullOrBlank()) return
        if (memberId.isBlank()) return
        if (memberId == ownerId) return

        _uiState.update {
            it.copy(
                selectedMemberForProfileId = null,
                selectedMemberForExpulsionId = memberId,
                errorMessage = null
            )
        }
    }

    fun onMemberClicked(memberId: String) {
        val state = _uiState.value
        val currentUserId = state.currentUserId
        if (memberId.isBlank()) return
        if (!currentUserId.isNullOrBlank() && memberId == currentUserId) return

        _uiState.update {
            it.copy(selectedMemberForProfileId = memberId)
        }
    }

    fun dismissMemberProfileDialog() {
        _uiState.update { it.copy(selectedMemberForProfileId = null) }
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
        _uiState.update { it.copy(selectedExpenseForDeletionId = null, errorMessage = null) }
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
        _uiState.update { it.copy(selectedMemberForExpulsionId = null, errorMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
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
