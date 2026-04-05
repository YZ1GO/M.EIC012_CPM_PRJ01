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
import kotlinx.coroutines.withTimeoutOrNull
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
    private var removeExistingReceiptImage: Boolean = false

    init {
        if (!editingExpenseId.isNullOrBlank()) {
            loadEditData()
        } else {
            loadGroupMembers()
        }
    }

    private fun loadEditData() {
        viewModelScope.launch {
            val expenseDeferred = async {
                withTimeoutOrNull(8000L) { loadExpenseDraft() }
                    ?: Result.failure(IllegalStateException("Timed out while loading expense details"))
            }

            val expenseResult = expenseDeferred.await()

            if (expenseResult.isFailure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = expenseResult.exceptionOrNull()?.message ?: "Could not load expense for editing"
                    )
                }
                return@launch
            }

            // Do not block edit actions on member/name enrichment when offline.
            _uiState.update { it.copy(isLoading = false, errorMessage = null) }

            val membersResult = withTimeoutOrNull(8000L) { loadGroupMembersForEditing() }
                ?: Result.failure(IllegalStateException("Timed out while loading group members"))

            if (membersResult.isFailure) {
                _uiState.update {
                    it.copy(errorMessage = membersResult.exceptionOrNull()?.message ?: "Could not load group members")
                }
            }
        }
    }

    private fun loadGroupMembers() {
        viewModelScope.launch {
            val currentUserId = withTimeoutOrNull(4000L) {
                authRepository.getCurrentUser().getOrNull()?.id.orEmpty()
            }.orEmpty()

            val membersResult = withTimeoutOrNull(5000L) {
                getAddExpenseMembersUseCase.execute(groupId)
            } ?: Result.failure(IllegalStateException("Timed out while loading group members"))

            membersResult
                .onSuccess { members ->
                    val resolvedDisplayNames = members.associateWith { memberId ->
                        when {
                            currentUserId.isNotBlank() && normalizeMemberId(memberId) == normalizeMemberId(currentUserId) -> "You"
                            else -> withTimeoutOrNull(1200L) {
                                authRepository.getUserDisplayName(memberId).getOrNull()
                            }
                                ?.takeIf { it.isNotBlank() }
                                ?: _uiState.value.memberDisplayNames[memberId]
                                ?: memberId
                        }
                    }
                    val memberDisplayNames = resolvedDisplayNames.withNormalizedAliases()
                    _uiState.update {
                        val updatedBuyerMode = if (editingExpenseId.isNullOrBlank()) {
                            BuyerMode.SINGLE_BUYER
                        } else {
                            it.buyerMode
                        }
                        val updatedPrimaryBuyerId = if (editingExpenseId.isNullOrBlank()) {
                            when {
                                currentUserId.isNotBlank() -> resolveMemberId(currentUserId, members).orEmpty()
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
                            currentUserId = currentUserId,
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
        val currentUserId = withTimeoutOrNull(4000L) {
            authRepository.getCurrentUser().getOrNull()?.id.orEmpty()
        }.orEmpty()

        val membersResult = withTimeoutOrNull(5000L) {
            getAddExpenseMembersUseCase.execute(groupId)
        } ?: Result.failure(IllegalStateException("Timed out while loading group members"))

        return membersResult
            .onSuccess { members ->
                val resolvedDisplayNames = members.associateWith { memberId ->
                    when {
                        currentUserId.isNotBlank() && normalizeMemberId(memberId) == normalizeMemberId(currentUserId) -> "You"
                        else -> withTimeoutOrNull(1200L) {
                            authRepository.getUserDisplayName(memberId).getOrNull()
                        }
                            ?.takeIf { it.isNotBlank() }
                            ?: _uiState.value.memberDisplayNames[memberId]
                            ?: memberId
                    }
                }
                val memberDisplayNames = resolvedDisplayNames.withNormalizedAliases()

                _uiState.update { current ->
                    val canonicalSelection = current.selectedSplitMemberIds
                        .mapNotNull { selectedId -> resolveMemberId(selectedId, members) }
                        .toSet()

                    val normalizedSplitSelection = when {
                        canonicalSelection.isEmpty() -> members.toSet()
                        canonicalSelection == members.toSet() -> members.toSet()
                        else -> canonicalSelection
                    }
                    val normalizedSplitMode = if (normalizedSplitSelection == members.toSet()) {
                        SplitMode.ALL_MEMBERS
                    } else {
                        SplitMode.SELECTED_MEMBERS
                    }

                    current.copy(
                        currentUserId = if (currentUserId.isBlank()) current.currentUserId else currentUserId,
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
        removeExistingReceiptImage = false
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
            val availableMembers = it.availablePayers.toSet()
            val hasMemberContext = availableMembers.isNotEmpty()
            val canonicalPrimaryPayer = if (hasMemberContext) {
                resolveMemberId(primaryPayer, it.availablePayers).orEmpty()
            } else {
                primaryPayer
            }
            val canonicalSelectedPayers = if (hasMemberContext) {
                canonicalizeMemberIdSet(selectedPayers, it.availablePayers)
            } else {
                selectedPayers
            }
            val canonicalContributionMap = if (hasMemberContext) {
                canonicalizeMemberKeyMap(contributionMap, it.availablePayers)
            } else {
                contributionMap
            }

            val isAllMembersSplit = if (!hasMemberContext) {
                true
            } else {
                splitSelection.isEmpty() || splitSelection == availableMembers
            }
            val resolvedSplitSelection = when {
                !hasMemberContext -> splitSelection
                isAllMembersSplit -> availableMembers
                else -> splitSelection
            }

            it.copy(
                isEditing = true,
                editingExpenseId = expense.id,
                amountInput = formatAmountInput(expense.amount),
                description = expense.description,
                buyerMode = if (canonicalSelectedPayers.size <= 1) BuyerMode.SINGLE_BUYER else BuyerMode.SELECT_BUYERS,
                primaryBuyerId = canonicalPrimaryPayer,
                selectedPayerIds = canonicalSelectedPayers,
                payerAmountInputs = canonicalContributionMap,
                splitMode = if (isAllMembersSplit) SplitMode.ALL_MEMBERS else SplitMode.SELECTED_MEMBERS,
                selectedSplitMemberIds = resolvedSplitSelection,
                hasReceiptImage = !expense.imagePath.isNullOrBlank(),
                receiptImagePath = expense.imagePath,
                detectedReceiptItems = expense.receiptItems,
                receiptMessage = if (canEditExpense) null else "Debt payment expenses can't be edited",
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
        removeExistingReceiptImage = imageBytes == null && _uiState.value.isEditing
        _uiState.update {
            it.copy(
                hasReceiptImage = imageBytes != null,
                receiptImagePath = null,
                detectedReceiptItems = emptyList(),
                receiptMessage = if (imageBytes != null) "Receipt attached" else null,
                errorMessage = null
            )
        }
    }

    fun preloadExistingReceiptImage(imageBytes: ByteArray) {
        receiptImageBytes = imageBytes
        removeExistingReceiptImage = false
        _uiState.update {
            if (it.hasReceiptImage) it else it.copy(hasReceiptImage = true)
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
            val canonicalPrimary = resolveMemberId(current.primaryBuyerId, current.availablePayers)
                ?: current.availablePayers.firstOrNull().orEmpty()
            val canonicalSelectedPayers = canonicalizeMemberIdSet(current.selectedPayerIds, current.availablePayers)
            val canonicalPayerAmounts = canonicalizeMemberKeyMap(current.payerAmountInputs, current.availablePayers)
            when (mode) {
                BuyerMode.SINGLE_BUYER -> current.copy(
                    buyerMode = mode,
                    primaryBuyerId = canonicalPrimary,
                    selectedPayerIds = if (canonicalPrimary.isBlank()) emptySet() else setOf(canonicalPrimary),
                    payerAmountInputs = if (canonicalPrimary.isBlank()) emptyMap() else mapOf(canonicalPrimary to current.amountInput),
                    selectedSplitMemberIds = if (canonicalPrimary.isBlank()) current.selectedSplitMemberIds else current.selectedSplitMemberIds + canonicalPrimary,
                    errorMessage = null
                )
                BuyerMode.SELECT_BUYERS -> {
                    val selected = if (canonicalSelectedPayers.isNotEmpty()) canonicalSelectedPayers else {
                        if (canonicalPrimary.isBlank()) emptySet() else setOf(canonicalPrimary)
                    }
                    val updatedAmounts = canonicalPayerAmounts.toMutableMap().apply {
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

            val canonicalValue = resolveMemberId(value, current.availablePayers) ?: value
            val canonicalSelected = canonicalizeMemberIdSet(current.selectedPayerIds, current.availablePayers)
            val canonicalPayerAmounts = canonicalizeMemberKeyMap(current.payerAmountInputs, current.availablePayers)

            val selectedPayers = canonicalSelected.toMutableSet().apply {
                if (checked) add(canonicalValue) else remove(canonicalValue)
            }

            val payerAmounts = canonicalPayerAmounts.toMutableMap().apply {
                if (checked) {
                    if (!containsKey(canonicalValue)) {
                        val defaultAmount = if (selectedPayers.size == 1) current.amountInput else ""
                        put(canonicalValue, defaultAmount)
                    }
                } else {
                    remove(canonicalValue)
                }
            }

            current.copy(
                selectedPayerIds = selectedPayers,
                payerAmountInputs = payerAmounts,
                selectedSplitMemberIds = if (checked) current.selectedSplitMemberIds + canonicalValue else current.selectedSplitMemberIds,
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
            val canonicalPrimary = resolveMemberId(current.primaryBuyerId, current.availablePayers)
                ?: current.primaryBuyerId
            val canonicalSelectedPayers = canonicalizeMemberIdSet(current.selectedPayerIds, current.availablePayers)
            val selected = if (mode == SplitMode.ALL_MEMBERS) {
                current.availablePayers.toSet()
            } else {
                val requiredPayers = when (current.buyerMode) {
                    BuyerMode.SINGLE_BUYER -> setOf(canonicalPrimary).filter { it.isNotBlank() }.toSet()
                    BuyerMode.SELECT_BUYERS -> canonicalSelectedPayers
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
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val freshMembers = withTimeoutOrNull(3000L) {
                getAddExpenseMembersUseCase.execute(groupId).getOrNull()
            }.orEmpty()
            val membersForValidation = if (state.availablePayers.isNotEmpty()) {
                state.availablePayers
            } else {
                freshMembers
            }

            if (membersForValidation.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Group members are unavailable. Please open the group again."
                    )
                }
                return@launch
            }

            val amount = state.amountInput.toDoubleOrNull()

            if (amount == null || amount <= 0.0) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Enter a valid amount") }
                return@launch
            }

            val payerContributions = mutableMapOf<String, Double>()
            if (state.buyerMode == BuyerMode.SINGLE_BUYER) {
                val resolvedPrimary = resolveMemberId(
                    preferredId = state.primaryBuyerId,
                    availableMemberIds = membersForValidation
                ) ?: resolveMemberId(
                    preferredId = state.primaryBuyerId,
                    availableMemberIds = freshMembers
                )

                if (resolvedPrimary.isNullOrBlank()) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Select a valid buyer") }
                    return@launch
                }
                payerContributions[resolvedPrimary] = amount
            } else {
                if (state.selectedPayerIds.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Select at least one payer") }
                    return@launch
                }

                state.selectedPayerIds.forEach { payerId ->
                    val resolvedPayerId = resolveMemberId(
                        preferredId = payerId,
                        availableMemberIds = membersForValidation
                    ) ?: resolveMemberId(
                        preferredId = payerId,
                        availableMemberIds = freshMembers
                    )

                    if (resolvedPayerId.isNullOrBlank()) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "One of the selected payers is no longer in this group"
                            )
                        }
                        return@launch
                    }

                    val contribution = state.payerAmountInputs[payerId]?.toDoubleOrNull()
                    if (contribution == null || contribution <= 0.0) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Enter valid contribution amounts for all selected payers"
                            )
                        }
                        return@launch
                    }
                    payerContributions[resolvedPayerId] = contribution
                }

                val contributionTotal = payerContributions.values.sum()
                if (kotlin.math.abs(contributionTotal - amount) > 0.009) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Payer contributions must match total amount") }
                    return@launch
                }
            }

            val requiredPayers = payerContributions.keys

            val splitMemberIds = if (state.splitMode == SplitMode.ALL_MEMBERS) {
                membersForValidation
            } else {
                (state.selectedSplitMemberIds + requiredPayers)
                    .mapNotNull { candidate ->
                        resolveMemberId(candidate, membersForValidation)
                            ?: resolveMemberId(candidate, freshMembers)
                    }
                    .distinct()
            }

            if (splitMemberIds.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Select at least one member to split with") }
                return@launch
            }

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
            val operationResult = withTimeoutOrNull(20000L) {
                if (state.isEditing && !editingId.isNullOrBlank()) {
                    requestUpdateExpenseUseCase.execute(
                        groupId = groupId,
                        expenseId = editingId,
                        amount = amount,
                        description = state.description,
                        splitMemberIds = splitMemberIds,
                        payerContributions = payerContributions,
                        receiptImageBytes = receiptImageBytes,
                        removeReceiptImage = removeExistingReceiptImage,
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
            } ?: Result.failure(
                IllegalStateException("Operation timed out. If you're offline, your connection status may not be stable.")
            )

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

private fun normalizeMemberId(value: String): String {
    return value.trim().substringAfterLast('/')
}

private fun resolveMemberId(preferredId: String, availableMemberIds: List<String>): String? {
    if (preferredId.isBlank()) return null
    val direct = availableMemberIds.firstOrNull { it == preferredId }
    if (direct != null) return direct

    val normalizedPreferred = normalizeMemberId(preferredId)
    return availableMemberIds.firstOrNull { normalizeMemberId(it) == normalizedPreferred }
}

private fun canonicalizeMemberIdSet(ids: Set<String>, availableMemberIds: List<String>): Set<String> {
    return ids.mapNotNull { id -> resolveMemberId(id, availableMemberIds) }.toSet()
}

private fun canonicalizeMemberKeyMap(
    valuesByMemberId: Map<String, String>,
    availableMemberIds: List<String>
): Map<String, String> {
    return valuesByMemberId.entries
        .mapNotNull { (memberId, value) ->
            resolveMemberId(memberId, availableMemberIds)?.let { canonicalId -> canonicalId to value }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.lastOrNull().orEmpty() }
}

private fun Map<String, String>.withNormalizedAliases(): Map<String, String> {
    val expanded = toMutableMap()
    entries.forEach { (memberId, label) ->
        val normalized = normalizeMemberId(memberId)
        if (normalized.isNotBlank() && !expanded.containsKey(normalized)) {
            expanded[normalized] = label
        }
    }
    return expanded
}
