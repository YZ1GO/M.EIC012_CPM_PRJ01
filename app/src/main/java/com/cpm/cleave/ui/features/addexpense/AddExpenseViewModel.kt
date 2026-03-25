package com.cpm.cleave.ui.features.addexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.domain.usecase.GetAddExpenseMembersUseCase
import com.cpm.cleave.domain.usecase.RequestCreateExpenseUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddExpenseViewModel(
    private val authRepository: IAuthRepository,
    private val getAddExpenseMembersUseCase: GetAddExpenseMembersUseCase,
    private val requestCreateExpenseUseCase: RequestCreateExpenseUseCase,
    private val groupId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState: StateFlow<AddExpenseUiState> = _uiState.asStateFlow()

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
        val amount = state.amountInput.toDoubleOrNull()

        if (amount == null || amount <= 0.0) {
            _uiState.update { it.copy(errorMessage = "Enter a valid amount") }
            return
        }

        val payerContributions = mutableMapOf<String, Double>()
        if (state.buyerMode == BuyerMode.SINGLE_BUYER) {
            val primary = state.primaryBuyerId
            if (primary.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Select a buyer") }
                return
            }
            payerContributions[primary] = amount
        } else {
            if (state.selectedPayerIds.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "Select at least one payer") }
                return
            }

            state.selectedPayerIds.forEach { payerId ->
                val contribution = state.payerAmountInputs[payerId]?.toDoubleOrNull()
                if (contribution == null || contribution <= 0.0) {
                    _uiState.update { it.copy(errorMessage = "Enter valid contribution amounts for all selected payers") }
                    return
                }
                payerContributions[payerId] = contribution
            }

            val contributionTotal = payerContributions.values.sum()
            if (kotlin.math.abs(contributionTotal - amount) > 0.009) {
                _uiState.update { it.copy(errorMessage = "Payer contributions must match total amount") }
                return
            }
        }

        val requiredPayers = if (state.buyerMode == BuyerMode.SINGLE_BUYER) {
            setOf(state.primaryBuyerId)
        } else {
            state.selectedPayerIds
        }

        val splitMemberIds = if (state.splitMode == SplitMode.ALL_MEMBERS) {
            state.availablePayers
        } else {
            (state.selectedSplitMemberIds + requiredPayers).toList()
        }

        if (splitMemberIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Select at least one member to split with") }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            requestCreateExpenseUseCase.execute(
                groupId = groupId,
                amount = amount,
                description = state.description,
                splitMemberIds = splitMemberIds,
                payerContributions = payerContributions
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
