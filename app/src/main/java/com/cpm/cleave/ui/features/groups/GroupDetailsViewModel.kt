package com.cpm.cleave.ui.features.groups

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.usecase.GetGroupDetailsUseCase
import com.cpm.cleave.domain.usecase.GroupDetailsData
import com.cpm.cleave.domain.usecase.RequestDeleteExpenseUseCase
import com.cpm.cleave.domain.usecase.RequestDeleteGroupUseCase
import com.cpm.cleave.domain.usecase.RequestExpelGroupMemberUseCase
import com.cpm.cleave.domain.usecase.RequestSettleDebtUseCase
import com.cpm.cleave.domain.usecase.RequestUpdateGroupUseCase
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Group
import java.util.Locale
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

    companion object {
        private const val TAG = "GroupDetailsVM"
        private const val INITIAL_RENDER_TIMEOUT_MS = 700L
        private val lastSnapshotByGroupId = mutableMapOf<String, GroupDetailsData>()
    }

    private var hasAppliedInitialSnapshot = false
    private var firstObserveSuccessAtMs: Long? = null
    private val recentDebtPaymentRequests = mutableMapOf<String, Long>()

    private val _uiState = MutableStateFlow(GroupDetailsUiState())
    val uiState: StateFlow<GroupDetailsUiState> = _uiState.asStateFlow()

    init {
        restoreLastSnapshotIfAvailable()
        observeGroupData()
    }

    private fun restoreLastSnapshotIfAvailable() {
        val cached = lastSnapshotByGroupId[groupId] ?: return
        _uiState.update {
            it.copy(
                isLoading = true,
                currentUserId = cached.currentUserId,
                group = cached.group,
                expenses = cached.expenses,
                debts = cached.debts,
                debtsWithReason = cached.debtsWithReason,
                totalYouOwe = cached.totalYouOwe,
                totalOwedToYou = cached.totalOwedToYou,
                userDisplayNames = cached.userDisplayNames,
                userPhotoUrls = cached.userPhotoUrls,
                userLastSeen = cached.userLastSeen,
                canDeleteGroup = !cached.group.ownerId.isNullOrBlank() && cached.group.ownerId == cached.currentUserId,
                editedGroupName = if (it.isEditingGroup) it.editedGroupName else cached.group.name
            )
        }
        // Keep gate active so initial live emissions still need a coherent snapshot.
        hasAppliedInitialSnapshot = false
        firstObserveSuccessAtMs = null
    }

    private fun observeGroupData() {
        viewModelScope.launch {
            getGroupDetailsUseCase.observe(groupId)
                .catch { error ->
                    Log.w(TAG, "observeGroupData catch groupId=$groupId error=${error.message}", error)
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
                            if (!hasAppliedInitialSnapshot) {
                                val now = System.currentTimeMillis()
                                if (firstObserveSuccessAtMs == null) firstObserveSuccessAtMs = now
                                val elapsed = now - (firstObserveSuccessAtMs ?: now)

                                val hasCoherentContent =
                                    data.group.id.isNotBlank() &&
                                        (data.group.members.isNotEmpty() || data.expenses.isNotEmpty() || data.debts.isNotEmpty())
                                val allowEmptyFallback =
                                    elapsed >= INITIAL_RENDER_TIMEOUT_MS &&
                                        data.group.id.isNotBlank()

                                if (!hasCoherentContent && !allowEmptyFallback) {
                                    Log.d(
                                        TAG,
                                        "defer initial render groupId=$groupId elapsedMs=$elapsed expenses=${data.expenses.size} debts=${data.debts.size} debtsWithReason=${data.debtsWithReason.size}"
                                    )
                                    return@onSuccess
                                }

                                hasAppliedInitialSnapshot = true
                                Log.d(
                                    TAG,
                                    "apply initial render groupId=$groupId elapsedMs=$elapsed coherent=$hasCoherentContent emptyFallback=$allowEmptyFallback"
                                )
                            }

                            val emptyReasons = data.debtsWithReason.count { it.reasons.isEmpty() }
                            Log.d(
                                TAG,
                                "collect success groupId=$groupId expenses=${data.expenses.size} debts=${data.debts.size} debtsWithReason=${data.debtsWithReason.size} debtsWithoutReasons=$emptyReasons"
                            )
                            _uiState.update {
                                val prevExpenses = it.expenses.size
                                val prevDebts = it.debts.size
                                val prevDebtsWithReason = it.debtsWithReason.size
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
                                val stableExpenses = if (data.expenses.isNotEmpty() || it.expenses.isEmpty()) {
                                    data.expenses
                                } else {
                                    it.expenses
                                }
                                // Trust newest debt snapshot, including valid transitions to no debts after settlement.
                                val stableDebts = data.debts
                                val stableDebtsWithReason = data.debtsWithReason
                                val stableCurrentUserId = data.currentUserId ?: it.currentUserId
                                val stableTotalYouOwe = if (data.currentUserId != null) data.totalYouOwe else it.totalYouOwe
                                val stableTotalOwedToYou = if (data.currentUserId != null) data.totalOwedToYou else it.totalOwedToYou
                                val stableUserDisplayNames = mergeDisplayNames(
                                    previous = it.userDisplayNames,
                                    incoming = data.userDisplayNames,
                                    currentUserId = stableCurrentUserId
                                )
                                val stableUserPhotoUrls = mergePhotoUrls(
                                    previous = it.userPhotoUrls,
                                    incoming = data.userPhotoUrls
                                )

                                val nextState = it.copy(
                                    isLoading = false,
                                    currentUserId = stableCurrentUserId,
                                    group = data.group,
                                    expenses = stableExpenses,
                                    debts = stableDebts,
                                    debtsWithReason = stableDebtsWithReason,
                                    totalYouOwe = stableTotalYouOwe,
                                    totalOwedToYou = stableTotalOwedToYou,
                                    userDisplayNames = stableUserDisplayNames,
                                    userPhotoUrls = stableUserPhotoUrls,
                                    userLastSeen = data.userLastSeen,
                                    canDeleteGroup = !data.group.ownerId.isNullOrBlank() && data.group.ownerId == stableCurrentUserId,
                                    editedGroupName = if (it.isEditingGroup) it.editedGroupName else data.group.name,
                                    errorMessage = preservedError
                                )
                                Log.d(
                                    TAG,
                                    "ui update groupId=$groupId expenses=$prevExpenses->${nextState.expenses.size} debts=$prevDebts->${nextState.debts.size} debtsWithReason=$prevDebtsWithReason->${nextState.debtsWithReason.size}"
                                )
                                if (nextState.expenses.isNotEmpty() || nextState.debts.isNotEmpty()) {
                                    lastSnapshotByGroupId[groupId] = toCachedSnapshot(data, nextState)
                                }
                                nextState
                            }
                        }
                        .onFailure { error ->
                            Log.w(TAG, "collect failure groupId=$groupId error=${error.message}", error)
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
        val currentState = _uiState.value
        val hasVisibleContent =
            currentState.group != null ||
                currentState.expenses.isNotEmpty() ||
                currentState.debts.isNotEmpty() ||
                currentState.debtsWithReason.isNotEmpty()

        if (!hasVisibleContent) {
            hasAppliedInitialSnapshot = false
            firstObserveSuccessAtMs = null
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        } else {
            _uiState.update { it.copy(errorMessage = null) }
        }

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
                        val stableExpenses = if (data.expenses.isNotEmpty() || it.expenses.isEmpty()) {
                            data.expenses
                        } else {
                            it.expenses
                        }
                        // Trust newest debt snapshot, including valid transitions to no debts after settlement.
                        val stableDebts = data.debts
                        val stableDebtsWithReason = data.debtsWithReason
                        val stableCurrentUserId = data.currentUserId ?: it.currentUserId
                        val stableTotalYouOwe = if (data.currentUserId != null) data.totalYouOwe else it.totalYouOwe
                        val stableTotalOwedToYou = if (data.currentUserId != null) data.totalOwedToYou else it.totalOwedToYou
                        val stableUserDisplayNames = mergeDisplayNames(
                            previous = it.userDisplayNames,
                            incoming = data.userDisplayNames,
                            currentUserId = stableCurrentUserId
                        )
                        val stableUserPhotoUrls = mergePhotoUrls(
                            previous = it.userPhotoUrls,
                            incoming = data.userPhotoUrls
                        )
                        val nextState = it.copy(
                            isLoading = false,
                            currentUserId = stableCurrentUserId,
                            group = data.group,
                            expenses = stableExpenses,
                            debts = stableDebts,
                            debtsWithReason = stableDebtsWithReason,
                            totalYouOwe = stableTotalYouOwe,
                            totalOwedToYou = stableTotalOwedToYou,
                            userDisplayNames = stableUserDisplayNames,
                            userPhotoUrls = stableUserPhotoUrls,
                            userLastSeen = data.userLastSeen,
                            canDeleteGroup = !data.group.ownerId.isNullOrBlank() && data.group.ownerId == stableCurrentUserId,
                            editedGroupName = if (it.isEditingGroup) it.editedGroupName else data.group.name,
                            errorMessage = preservedError
                        )
                        if (nextState.expenses.isNotEmpty() || nextState.debts.isNotEmpty()) {
                            lastSnapshotByGroupId[groupId] = toCachedSnapshot(data, nextState)
                        }
                        nextState
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

    private fun toCachedSnapshot(data: GroupDetailsData, state: GroupDetailsUiState): GroupDetailsData {
        return GroupDetailsData(
            group = data.group,
            expenses = state.expenses,
            debts = state.debts,
            debtsWithReason = state.debtsWithReason,
            totalYouOwe = state.totalYouOwe,
            totalOwedToYou = state.totalOwedToYou,
            userDisplayNames = state.userDisplayNames,
            userPhotoUrls = data.userPhotoUrls,
            userLastSeen = data.userLastSeen,
            currentUserId = state.currentUserId
        )
    }

    private fun mergeDisplayNames(
        previous: Map<String, String>,
        incoming: Map<String, String>,
        currentUserId: String?
    ): Map<String, String> {
        val merged = previous.toMutableMap()

        incoming.forEach { (userId, incomingName) ->
            val previousName = previous[userId].orEmpty()
            val normalizedUserId = normalizeIdentity(userId)
            val normalizedCurrentUserId = currentUserId?.let(::normalizeIdentity).orEmpty()

            val finalName = when {
                normalizedCurrentUserId.isNotBlank() && normalizedUserId == normalizedCurrentUserId -> "You"
                incomingName.isBlank() -> previousName
                isGenericDisplayName(incomingName) && previousName.isNotBlank() && !isGenericDisplayName(previousName) -> previousName
                else -> incomingName
            }

            if (finalName.isNotBlank()) {
                merged[userId] = finalName
            }
        }

        return merged
    }

    private fun mergePhotoUrls(
        previous: Map<String, String>,
        incoming: Map<String, String>
    ): Map<String, String> {
        val merged = previous.toMutableMap()

        incoming.forEach { (userId, incomingUrl) ->
            if (incomingUrl.isBlank()) return@forEach

            val normalizedIncomingId = normalizeIdentity(userId)
            val existingEntryKey = merged.keys.firstOrNull { existingKey ->
                normalizeIdentity(existingKey) == normalizedIncomingId
            }

            if (existingEntryKey != null) {
                merged[existingEntryKey] = incomingUrl
            } else {
                merged[userId] = incomingUrl
            }
        }

        return merged
    }

    private fun isGenericDisplayName(value: String): Boolean {
        val normalized = value.trim()
        return normalized.equals("User", ignoreCase = true) ||
            normalized.equals("Guest", ignoreCase = true)
    }

    private fun normalizeIdentity(value: String): String {
        return value.trim().substringAfterLast('/')
    }

    fun onDebtClicked(debt: Debt) {
        val state = _uiState.value
        if (state.isSettlingDebt) return
        if (state.currentUserId.isNullOrBlank()) return
        if (state.currentUserId != debt.fromUser) return

        _uiState.update {
            it.copy(
                selectedDebtForPayment = debt,
                debtPaymentAmountInput = String.format(Locale.getDefault(), "%.2f", debt.amount),
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

        val paymentRequestKey = buildDebtPaymentRequestKey(selectedDebt, amount)
        val now = System.currentTimeMillis()
        // Prevent accidental duplicate submissions of the same payment while offline sync catches up.
        if (recentDebtPaymentRequests[paymentRequestKey]?.let { now - it < 120_000L } == true) {
            _uiState.update { it.copy(errorMessage = "Payment already submitted. Please wait a moment.") }
            return
        }
        recentDebtPaymentRequests[paymentRequestKey] = now

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
                    recentDebtPaymentRequests.remove(paymentRequestKey)
                    _uiState.update {
                        it.copy(
                            isSettlingDebt = false,
                            errorMessage = error.message ?: "Could not register payment"
                        )
                    }
                }
        }
    }

    private fun buildDebtPaymentRequestKey(debt: Debt, amount: Double): String {
        val from = normalizeIdentity(debt.fromUser)
        val to = normalizeIdentity(debt.toUser)
        val cents = kotlin.math.round(amount * 100.0).toLong()
        return "$groupId|$from|$to|$cents"
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
                editedCurrencyCode = group.currency,
                errorMessage = null
            )
        }
    }

    fun onEditedGroupNameChanged(value: String) {
        _uiState.update { it.copy(editedGroupName = value, errorMessage = null) }
    }

    fun onEditedCurrencyChanged(value: String) {
        _uiState.update { it.copy(editedCurrencyCode = value, errorMessage = null) }
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

        val trimmedCurrency = state.editedCurrencyCode.trim().uppercase()
        if (trimmedCurrency.length != 3) {
            _uiState.update { it.copy(errorMessage = "Please select a valid currency.") }
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
                currency = trimmedCurrency,
                imageUrl = uploadedImageUrl
            )

            requestUpdateGroupUseCase.execute(updatedGroup)
                .onSuccess { savedGroup ->
                    _uiState.update {
                        it.copy(
                            isUpdatingGroup = false,
                            isEditingGroup = false,
                            editedGroupName = savedGroup.name,
                            editedCurrencyCode = savedGroup.currency
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
