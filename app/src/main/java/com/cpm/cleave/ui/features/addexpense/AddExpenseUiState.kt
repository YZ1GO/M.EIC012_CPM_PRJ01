package com.cpm.cleave.ui.features.addexpense

enum class SplitMode {
    ALL_MEMBERS,
    SELECTED_MEMBERS
}

enum class BuyerMode {
    SINGLE_BUYER,
    SELECT_BUYERS
}

data class AddExpenseUiState(
    val amountInput: String = "",
    val description: String = "",
    val availablePayers: List<String> = emptyList(),
    val buyerMode: BuyerMode = BuyerMode.SINGLE_BUYER,
    val primaryBuyerId: String = "",
    val selectedPayerIds: Set<String> = emptySet(),
    val payerAmountInputs: Map<String, String> = emptyMap(),
    val splitMode: SplitMode = SplitMode.ALL_MEMBERS,
    val selectedSplitMemberIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
