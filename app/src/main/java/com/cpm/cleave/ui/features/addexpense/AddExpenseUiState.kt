package com.cpm.cleave.ui.features.addexpense

import com.cpm.cleave.model.ReceiptItem

enum class SplitMode {
    ALL_MEMBERS,
    SELECTED_MEMBERS
}

enum class BuyerMode {
    SINGLE_BUYER,
    SELECT_BUYERS
}

data class AddExpenseUiState(
    val isEditing: Boolean = false,
    val editingExpenseId: String? = null,
    val canEditExpense: Boolean = true,
    val amountInput: String = "",
    val description: String = "",
    val availablePayers: List<String> = emptyList(),
    val memberDisplayNames: Map<String, String> = emptyMap(),
    val buyerMode: BuyerMode = BuyerMode.SINGLE_BUYER,
    val primaryBuyerId: String = "",
    val selectedPayerIds: Set<String> = emptySet(),
    val payerAmountInputs: Map<String, String> = emptyMap(),
    val splitMode: SplitMode = SplitMode.ALL_MEMBERS,
    val selectedSplitMemberIds: Set<String> = emptySet(),
    val hasReceiptImage: Boolean = false,
    val receiptImagePath: String? = null,
    val isExtractingTotal: Boolean = false,
    val isExtractingItems: Boolean = false,
    val detectedReceiptItems: List<ReceiptItem> = emptyList(),
    val receiptMessage: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
