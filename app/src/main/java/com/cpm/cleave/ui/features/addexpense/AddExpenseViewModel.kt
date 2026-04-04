package com.cpm.cleave.ui.features.addexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import com.cpm.cleave.domain.usecase.GetEditableExpenseUseCase
import com.cpm.cleave.domain.usecase.GetAddExpenseMembersUseCase
import com.cpm.cleave.domain.usecase.RequestCreateExpenseUseCase
import com.cpm.cleave.domain.usecase.RequestUpdateExpenseUseCase
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ReceiptItem
import com.cpm.cleave.model.isDebtSettlementExpense
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class AddExpenseViewModel(
    private val authRepository: IAuthRepository,
    private val scannerRepository: IScannerRepository,
    private val getAddExpenseMembersUseCase: GetAddExpenseMembersUseCase,
    private val requestCreateExpenseUseCase: RequestCreateExpenseUseCase,
    private val requestUpdateExpenseUseCase: RequestUpdateExpenseUseCase,
    private val getEditableExpenseUseCase: GetEditableExpenseUseCase,
    private val groupId: String,
    private val editingExpenseId: String? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AddExpenseUiState(
            isEditing = !editingExpenseId.isNullOrBlank(),
            editingExpenseId = editingExpenseId,
            isLoading = !editingExpenseId.isNullOrBlank()
        )
    )
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()
    private var receiptImageBytes: ByteArray? = null

    init {
        if (!editingExpenseId.isNullOrBlank()) {
            loadEditData()
        } else {
            loadGroupMembers()
        }
    }

    private fun loadEditData() {
        viewModelScope.launch {
            val expenseDeferred = async { loadExpenseDraft() }
            val membersDeferred = async { loadGroupMembersForEditing() }

            val expenseResult = expenseDeferred.await()
            val membersResult = membersDeferred.await()

            if (expenseResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = expenseResult.exceptionOrNull()?.message ?: "Could not load expense for editing"
                    )
                }
                return@launch
            }

            if (membersResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = membersResult.exceptionOrNull()?.message ?: "Could not load group members"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoading = false, errorMessage = null) }
        }
    }

    private fun loadGroupMembers() {
        viewModelScope.launch {
            val currentUserId = authRepository.getCurrentUser().getOrNull()?.id.orEmpty()
            getAddExpenseMembersUseCase.execute(groupId)
                .onSuccess { members ->
                    val memberDisplayNames = members.associateWith { memberId ->
                        when {
                            memberId == currentUserId -> "You"
                            else -> authRepository.getUserDisplayName(memberId).getOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?: memberId
                        }
                    }
                    _uiState.update {
                        val updatedBuyerMode = if (editingExpenseId.isNullOrBlank()) {
                            BuyerMode.SINGLE_BUYER
                        } else {
                            it.buyerMode
                        }
                        val updatedPrimaryBuyerId = if (editingExpenseId.isNullOrBlank()) {
                            when {
                                currentUserId.isNotBlank() && members.contains(currentUserId) -> currentUserId
                                else -> members.firstOrNull().orEmpty()
                            }
                        } else {
                            it.primaryBuyerId
                        }
                        val updatedSelectedPayers = if (editingExpenseId.isNullOrBlank()) {
                            if (updatedPrimaryBuyerId.isBlank()) emptySet() else setOf(updatedPrimaryBuyerId)
                        } else {
                            it.selectedPayerIds
                        }
                        val updatedPayerAmounts = if (editingExpenseId.isNullOrBlank()) {
                            if (updatedPrimaryBuyerId.isBlank()) emptyMap() else mapOf(updatedPrimaryBuyerId to "")
                        } else {
                            it.payerAmountInputs
                        }

                        it.copy(
                            isEditing = !editingExpenseId.isNullOrBlank(),
                            editingExpenseId = editingExpenseId,
                            availablePayers = members,
                            memberDisplayNames = memberDisplayNames,
                            buyerMode = updatedBuyerMode,
                            primaryBuyerId = updatedPrimaryBuyerId,
                            selectedPayerIds = updatedSelectedPayers,
                            payerAmountInputs = updatedPayerAmounts,
                            selectedSplitMemberIds = if (editingExpenseId.isNullOrBlank()) members.toSet() else it.selectedSplitMemberIds
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

    private suspend fun loadGroupMembersForEditing(): Result<Unit> {
        val currentUserId = authRepository.getCurrentUser().getOrNull()?.id.orEmpty()
        return getAddExpenseMembersUseCase.execute(groupId)
            .onSuccess { members ->
                val memberDisplayNames = members.associateWith { memberId ->
                    when {
                        memberId == currentUserId -> "You"
                        else -> authRepository.getUserDisplayName(memberId).getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: memberId
                    }
                }

                _uiState.update { current ->
                    val normalizedSplitSelection = when {
                        current.selectedSplitMemberIds.isEmpty() -> members.toSet()
                        current.selectedSplitMemberIds == members.toSet() -> members.toSet()
                        else -> current.selectedSplitMemberIds
                    }
                    val normalizedSplitMode = if (normalizedSplitSelection == members.toSet()) {
                        SplitMode.ALL_MEMBERS
                    } else {
                        SplitMode.SELECTED_MEMBERS
                    }

                    current.copy(
                        availablePayers = members,
                        memberDisplayNames = memberDisplayNames,
                        splitMode = normalizedSplitMode,
                        selectedSplitMemberIds = normalizedSplitSelection
                    )
                }
            }
            .map { Unit }
    }

    private suspend fun loadExpenseDraft(): Result<Unit> {
        val expenseId = editingExpenseId ?: return Result.failure(IllegalStateException("Missing expense id"))
        var hasPrefilledFromClick = false

        EditExpensePrefillStore.take(groupId, expenseId)?.let { payload ->
            _uiState.update {
                it.copy(memberDisplayNames = payload.displayNames)
            }
            applyExpenseDraft(
                expense = payload.expense,
                splitMemberIds = emptySet()
            )
            hasPrefilledFromClick = true
        }

        var lastFailure: Throwable? = null

        for (attempt in 0 until 5) {
            val result = getEditableExpenseUseCase.execute(groupId = groupId, expenseId = expenseId)
            if (result.isSuccess) {
                result.onSuccess { editable ->
                    applyExpenseDraft(
                        expense = editable.expense,
                        splitMemberIds = editable.splitMemberIds
                    )
                }
                return Result.success(Unit)
            }

            lastFailure = result.exceptionOrNull()
            val shouldRetry = lastFailure?.message?.contains("Expense not found", ignoreCase = true) == true && attempt < 4
            if (shouldRetry) {
                delay(120)
            } else {
                break
            }
        }

        if (hasPrefilledFromClick) {
            // Keep the prefilled edit form usable when repository data is transiently unavailable.
            return Result.success(Unit)
        }

        return Result.failure(lastFailure ?: IllegalArgumentException("Expense not found"))
    }

    private fun applyExpenseDraft(expense: Expense, splitMemberIds: Set<String>) {
        val canEditExpense = !expense.isDebtSettlementExpense()
        val contributions = expense.payerContributions.filter { contribution -> contribution.amount > 0.0 }
        val selectedPayers = if (contributions.isNotEmpty()) {
            contributions.map { it.userId }.toSet()
        } else if (expense.paidByUserId.isNotBlank()) {
            setOf(expense.paidByUserId)
        } else {
            emptySet()
        }

        val primaryPayer = contributions.maxByOrNull { it.amount }?.userId
            ?: expense.paidByUserId

        val contributionMap = when {
            contributions.isNotEmpty() -> contributions.associate { contribution ->
                contribution.userId to formatAmountInput(contribution.amount)
            }
            primaryPayer.isNotBlank() -> mapOf(primaryPayer to formatAmountInput(expense.amount))
            else -> emptyMap()
        }

        val splitSelection = splitMemberIds

        _uiState.update {
            it.copy(
                isEditing = true,
                editingExpenseId = expense.id,
                amountInput = formatAmountInput(expense.amount),
                description = expense.description,
                buyerMode = if (selectedPayers.size <= 1) BuyerMode.SINGLE_BUYER else BuyerMode.SELECT_BUYERS,
                primaryBuyerId = primaryPayer,
                selectedPayerIds = selectedPayers,
                payerAmountInputs = contributionMap,
                splitMode = if (splitSelection.isEmpty()) SplitMode.ALL_MEMBERS else SplitMode.SELECTED_MEMBERS,
                selectedSplitMemberIds = splitSelection,
                hasReceiptImage = !expense.imagePath.isNullOrBlank(),
                detectedReceiptItems = expense.receiptItems,
                receiptMessage = if (canEditExpense) "Editing existing expense" else "Debt payment expenses can't be edited",
                canEditExpense = canEditExpense,
                errorMessage = if (canEditExpense) null else "Debt payment expenses can't be edited"
            )
        }
    }

    private fun isExpenseEditable(): Boolean {
        val state = _uiState.value
        return !state.isEditing || state.canEditExpense
    }

    private fun formatAmountInput(value: Double): String {
        return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    fun onAmountChanged(value: String) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            val updatedPayerAmounts = when (current.buyerMode) {
                BuyerMode.SINGLE_BUYER -> {
                    if (current.primaryBuyerId.isBlank()) current.payerAmountInputs
                    else current.payerAmountInputs + (current.primaryBuyerId to value)
                }
                BuyerMode.SELECT_BUYERS -> {
                    rescalePayerAmounts(
                        selectedPayerIds = current.selectedPayerIds,
                        currentInputs = current.payerAmountInputs,
                        newTotalValue = value
                    )
                }
            }
            current.copy(amountInput = value, payerAmountInputs = updatedPayerAmounts, errorMessage = null)
        }
    }

    private fun rescalePayerAmounts(
        selectedPayerIds: Set<String>,
        currentInputs: Map<String, String>,
        newTotalValue: String
    ): Map<String, String> {
        if (selectedPayerIds.isEmpty()) return currentInputs
        val newTotal = newTotalValue.toDoubleOrNull() ?: return currentInputs

        if (selectedPayerIds.size == 1) {
            val onlyPayerId = selectedPayerIds.first()
            return currentInputs + (onlyPayerId to formatAmountInput(newTotal))
        }

        val parsedAmounts = selectedPayerIds.mapNotNull { payerId ->
            currentInputs[payerId]?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { payerId to it }
        }

        val totalCurrent = parsedAmounts.sumOf { it.second }
        val fallbackShare = newTotal / selectedPayerIds.size

        return selectedPayerIds.associateWith { payerId ->
            val currentAmount = currentInputs[payerId]?.toDoubleOrNull()?.takeIf { it >= 0.0 }
            val scaledAmount = if (totalCurrent > 0.0 && currentAmount != null) {
                newTotal * (currentAmount / totalCurrent)
            } else {
                fallbackShare
            }
            formatAmountInput(scaledAmount)
        }
    }

    fun onDescriptionChanged(value: String) {
        if (!isExpenseEditable()) return
        _uiState.update { it.copy(description = value, errorMessage = null) }
    }

    fun onReceiptImageSelected(imageBytes: ByteArray?) {
        if (!isExpenseEditable()) return
        receiptImageBytes = imageBytes
        _uiState.update {
            it.copy(
                hasReceiptImage = imageBytes != null,
                detectedReceiptItems = emptyList(),
                receiptMessage = if (imageBytes != null) "Receipt attached" else null,
                errorMessage = null
            )
        }
    }

    fun setErrorMessage(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun extractTotalFromReceipt() {
        if (!isExpenseEditable()) return
        val imageBytes = receiptImageBytes
        if (imageBytes == null) {
            _uiState.update { it.copy(errorMessage = "Capture a receipt image first") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isExtractingTotal = true, receiptMessage = null, errorMessage = null) }
            scannerRepository.extractReceiptTotal(imageBytes)
                .onSuccess { total ->
                    if (total != null && total > 0.0) {
                        onAmountChanged(String.format(Locale.US, "%.2f", total))
                        _uiState.update {
                            it.copy(
                                isExtractingTotal = false,
                                receiptMessage = "Extracted total: ${String.format(Locale.US, "%.2f", total)}"
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isExtractingTotal = false,
                                receiptMessage = "Could not detect a total. You can still type it manually."
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isExtractingTotal = false,
                            errorMessage = error.message ?: "Could not read receipt total"
                        )
                    }
                }
        }
    }

    fun extractItemsFromReceipt() {
        if (!isExpenseEditable()) return
        val imageBytes = receiptImageBytes
        if (imageBytes == null) {
            _uiState.update { it.copy(errorMessage = "Capture a receipt image first") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isExtractingItems = true, receiptMessage = null, errorMessage = null) }
            scannerRepository.extractReceiptItems(imageBytes)
                .onSuccess { items ->
                    _uiState.update {
                        it.copy(
                            isExtractingItems = false,
                            detectedReceiptItems = items,
                            receiptMessage = if (items.isNotEmpty()) {
                                "Detected ${items.size} line items"
                            } else {
                                "No item lines detected. You can still add them manually."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isExtractingItems = false,
                            errorMessage = error.message ?: "Could not read receipt items"
                        )
                    }
                }
        }
    }

    fun onReceiptItemNameChanged(index: Int, value: String) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList()
            updated[index] = updated[index].copy(name = value)
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun onReceiptItemAmountChanged(index: Int, value: String) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList()
            val parsed = value.toDoubleOrNull() ?: 0.0
            updated[index] = updated[index].copy(amount = parsed)
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun onReceiptItemQuantityChanged(index: Int, value: String) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList()
            val parsed = value.toDoubleOrNull()
            updated[index] = updated[index].copy(quantity = parsed?.takeIf { it > 0.0 } ?: 1.0)
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun addReceiptItem() {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            current.copy(
                detectedReceiptItems = current.detectedReceiptItems + ReceiptItem(name = "", amount = 0.0, quantity = 1.0, unitPrice = null)
            )
        }
    }

    fun removeReceiptItem(index: Int) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList().apply { removeAt(index) }
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun fillDescriptionFromReceiptItems() {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            val names = current.detectedReceiptItems
                .map { it.name.trim() }
                .filter { it.isNotBlank() }
            if (names.isEmpty()) return@update current

            val merged = names.joinToString(separator = ", ").take(120)
            current.copy(description = merged)
        }
    }

    fun onBuyerModeChanged(mode: BuyerMode) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            val primary = current.primaryBuyerId.ifBlank { current.availablePayers.firstOrNull().orEmpty() }
            when (mode) {
                BuyerMode.SINGLE_BUYER -> current.copy(
                    buyerMode = mode,
                    primaryBuyerId = primary,
                    selectedPayerIds = if (primary.isBlank()) emptySet() else setOf(primary),
                    payerAmountInputs = if (primary.isBlank()) emptyMap() else mapOf(primary to current.amountInput),
                    selectedSplitMemberIds = current.selectedSplitMemberIds + primary,
                    errorMessage = null
                )
                BuyerMode.SELECT_BUYERS -> {
                    val selected = if (current.selectedPayerIds.isNotEmpty()) current.selectedPayerIds else {
                        if (primary.isBlank()) emptySet() else setOf(primary)
                    }
                    val updatedAmounts = current.payerAmountInputs.toMutableMap().apply {
                        if (selected.size == 1) {
                            val onlyPayerId = selected.first()
                            putIfAbsent(onlyPayerId, current.amountInput)
                        }
                    }
                    current.copy(
                        buyerMode = mode,
                        selectedPayerIds = selected,
                        payerAmountInputs = updatedAmounts,
                        selectedSplitMemberIds = current.selectedSplitMemberIds + selected,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun onPayerToggled(value: String, checked: Boolean) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            if (current.buyerMode == BuyerMode.SINGLE_BUYER) return@update current

            val selectedPayers = current.selectedPayerIds.toMutableSet().apply {
                if (checked) add(value) else remove(value)
            }

            val payerAmounts = current.payerAmountInputs.toMutableMap().apply {
                if (checked) {
                    if (!containsKey(value)) {
                        val defaultAmount = if (selectedPayers.size == 1) current.amountInput else ""
                        put(value, defaultAmount)
                    }
                } else {
                    remove(value)
                }
            }

            current.copy(
                selectedPayerIds = selectedPayers,
                payerAmountInputs = payerAmounts,
                selectedSplitMemberIds = if (checked) current.selectedSplitMemberIds + value else current.selectedSplitMemberIds,
                errorMessage = null
            )
        }
    }

    fun onPayerAmountChanged(memberId: String, value: String) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            if (current.buyerMode == BuyerMode.SINGLE_BUYER) return@update current
            if (!current.selectedPayerIds.contains(memberId)) return@update current
            current.copy(
                payerAmountInputs = current.payerAmountInputs + (memberId to value),
                errorMessage = null
            )
        }
    }

    fun onSplitModeChanged(mode: SplitMode) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            val selected = if (mode == SplitMode.ALL_MEMBERS) {
                current.availablePayers.toSet()
            } else {
                val requiredPayers = when (current.buyerMode) {
                    BuyerMode.SINGLE_BUYER -> setOf(current.primaryBuyerId).filter { it.isNotBlank() }.toSet()
                    BuyerMode.SELECT_BUYERS -> current.selectedPayerIds
                }
                (current.selectedSplitMemberIds.ifEmpty { current.availablePayers.toSet() } + requiredPayers)
            }
            current.copy(splitMode = mode, selectedSplitMemberIds = selected, errorMessage = null)
        }
    }

    fun onSplitMemberToggled(memberId: String, checked: Boolean) {
        if (!isExpenseEditable()) return
        _uiState.update { current ->
            val requiredPayers = when (current.buyerMode) {
                BuyerMode.SINGLE_BUYER -> setOf(current.primaryBuyerId).filter { it.isNotBlank() }.toSet()
                BuyerMode.SELECT_BUYERS -> current.selectedPayerIds
            }

            if (requiredPayers.contains(memberId) && !checked) {
                return@update current
            }
            val updatedSelection = current.selectedSplitMemberIds.toMutableSet().apply {
                if (checked) add(memberId) else remove(memberId)
            }
            current.copy(selectedSplitMemberIds = updatedSelection, errorMessage = null)
        }
    }

    fun submitExpense(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.isEditing && !state.canEditExpense) {
            _uiState.update { it.copy(errorMessage = "Debt payment expenses can't be edited") }
            return
        }

        viewModelScope.launch {
            val freshMembersResult = getAddExpenseMembersUseCase.execute(groupId)
            val freshMembers = freshMembersResult.getOrElse { error ->
                _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Group not found") }
                return@launch
            }

            val currentUserId = authRepository.getCurrentUser().getOrNull()?.id.orEmpty()
            if (currentUserId.isBlank() || currentUserId !in freshMembers) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "This group is no longer available."
                    )
                }
                return@launch
            }

            val amount = state.amountInput.toDoubleOrNull()

            if (amount == null || amount <= 0.0) {
                _uiState.update { it.copy(errorMessage = "Enter a valid amount") }
                return@launch
            }

            val payerContributions = mutableMapOf<String, Double>()
            if (state.buyerMode == BuyerMode.SINGLE_BUYER) {
                val primary = state.primaryBuyerId
                if (primary.isBlank() || primary !in freshMembers) {
                    _uiState.update { it.copy(errorMessage = "Select a valid buyer") }
                    return@launch
                }
                payerContributions[primary] = amount
            } else {
                if (state.selectedPayerIds.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = "Select at least one payer") }
                    return@launch
                }

                state.selectedPayerIds.forEach { payerId ->
                    if (payerId !in freshMembers) {
                        _uiState.update { it.copy(errorMessage = "One of the selected payers is no longer in this group") }
                        return@launch
                    }

                    val contribution = state.payerAmountInputs[payerId]?.toDoubleOrNull()
                    if (contribution == null || contribution <= 0.0) {
                        _uiState.update { it.copy(errorMessage = "Enter valid contribution amounts for all selected payers") }
                        return@launch
                    }
                    payerContributions[payerId] = contribution
                }

                val contributionTotal = payerContributions.values.sum()
                if (kotlin.math.abs(contributionTotal - amount) > 0.009) {
                    _uiState.update { it.copy(errorMessage = "Payer contributions must match total amount") }
                    return@launch
                }
            }

            val requiredPayers = if (state.buyerMode == BuyerMode.SINGLE_BUYER) {
                setOf(state.primaryBuyerId)
            } else {
                state.selectedPayerIds
            }

            val splitMemberIds = if (state.splitMode == SplitMode.ALL_MEMBERS) {
                freshMembers
            } else {
                (state.selectedSplitMemberIds + requiredPayers).filter { it in freshMembers }
            }

            if (splitMemberIds.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "Select at least one member to split with") }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true) }
            val normalizedReceiptItems = state.detectedReceiptItems
                .mapNotNull { item ->
                    val name = item.name.trim()
                    if (name.isBlank() || item.amount <= 0.0) null
                    else ReceiptItem(
                        name = name,
                        amount = item.amount,
                        quantity = item.quantity?.takeIf { it > 0.0 } ?: 1.0,
                        unitPrice = item.unitPrice
                    )
                }

            val editingId = state.editingExpenseId
            val operationResult = if (state.isEditing && !editingId.isNullOrBlank()) {
                requestUpdateExpenseUseCase.execute(
                    groupId = groupId,
                    expenseId = editingId,
                    amount = amount,
                    description = state.description,
                    splitMemberIds = splitMemberIds,
                    payerContributions = payerContributions,
                    receiptImageBytes = receiptImageBytes,
                    receiptItems = normalizedReceiptItems
                )
            } else {
                requestCreateExpenseUseCase.execute(
                    groupId = groupId,
                    amount = amount,
                    description = state.description,
                    splitMemberIds = splitMemberIds,
                    payerContributions = payerContributions,
                    receiptImageBytes = receiptImageBytes,
                    receiptItems = normalizedReceiptItems
                )
            }

            operationResult.onSuccess {
                _uiState.update { it.copy(isLoading = false) }
                onSuccess()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: if (state.isEditing) "Could not update expense" else "Could not create expense"
                    )
                }
            }
        }
    }
}
