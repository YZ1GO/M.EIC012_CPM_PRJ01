package com.cpm.cleave.ui.features.addexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import com.cpm.cleave.domain.usecase.GetAddExpenseMembersUseCase
import com.cpm.cleave.domain.usecase.RequestCreateExpenseUseCase
import com.cpm.cleave.model.ReceiptItem
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
    private val groupId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()
    private var receiptImageBytes: ByteArray? = null

    init {
        loadGroupMembers()
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
                    val defaultPayer = when {
                        currentUserId.isNotBlank() && members.contains(currentUserId) -> currentUserId
                        else -> members.firstOrNull().orEmpty()
                    }
                    _uiState.update {
                        it.copy(
                            availablePayers = members,
                            memberDisplayNames = memberDisplayNames,
                            buyerMode = BuyerMode.SINGLE_BUYER,
                            primaryBuyerId = defaultPayer,
                            selectedPayerIds = if (defaultPayer.isBlank()) emptySet() else setOf(defaultPayer),
                            payerAmountInputs = if (defaultPayer.isBlank()) emptyMap() else mapOf(defaultPayer to ""),
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
        _uiState.update { current ->
            val updatedPayerAmounts = when (current.buyerMode) {
                BuyerMode.SINGLE_BUYER -> {
                    if (current.primaryBuyerId.isBlank()) current.payerAmountInputs
                    else current.payerAmountInputs + (current.primaryBuyerId to value)
                }
                BuyerMode.SELECT_BUYERS -> {
                    if (current.selectedPayerIds.size == 1) {
                        val onlyPayerId = current.selectedPayerIds.first()
                        current.payerAmountInputs + (onlyPayerId to value)
                    } else {
                        current.payerAmountInputs
                    }
                }
            }
            current.copy(amountInput = value, payerAmountInputs = updatedPayerAmounts, errorMessage = null)
        }
    }

    fun onDescriptionChanged(value: String) {
        _uiState.update { it.copy(description = value, errorMessage = null) }
    }

    fun onReceiptImageSelected(imageBytes: ByteArray?) {
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
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList()
            updated[index] = updated[index].copy(name = value)
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun onReceiptItemAmountChanged(index: Int, value: String) {
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList()
            val parsed = value.toDoubleOrNull() ?: 0.0
            updated[index] = updated[index].copy(amount = parsed)
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun onReceiptItemQuantityChanged(index: Int, value: String) {
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList()
            val parsed = value.toDoubleOrNull()
            updated[index] = updated[index].copy(quantity = parsed?.takeIf { it > 0.0 } ?: 1.0)
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun addReceiptItem() {
        _uiState.update { current ->
            current.copy(
                detectedReceiptItems = current.detectedReceiptItems + ReceiptItem(name = "", amount = 0.0, quantity = 1.0, unitPrice = null)
            )
        }
    }

    fun removeReceiptItem(index: Int) {
        _uiState.update { current ->
            if (index !in current.detectedReceiptItems.indices) return@update current
            val updated = current.detectedReceiptItems.toMutableList().apply { removeAt(index) }
            current.copy(detectedReceiptItems = updated)
        }
    }

    fun fillDescriptionFromReceiptItems() {
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

    fun createExpense(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.isLoading) return

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
            requestCreateExpenseUseCase.execute(
                groupId = groupId,
                amount = amount,
                description = state.description,
                splitMemberIds = splitMemberIds,
                payerContributions = payerContributions,
                receiptImageBytes = receiptImageBytes,
                receiptItems = state.detectedReceiptItems
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
