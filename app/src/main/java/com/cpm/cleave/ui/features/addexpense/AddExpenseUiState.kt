package com.cpm.cleave.ui.features.addexpense

enum class SplitMode {
    ALL_MEMBERS,
    SELECTED_MEMBERS
}

// TODO(expense-advanced): add multi-payer support (e.g. A pays 60, B pays 40) with per-payer contributions.
data class AddExpenseUiState(
    val amountInput: String = "",
    val description: String = "",
    val payerId: String = "",
    val availablePayers: List<String> = emptyList(),
    val splitMode: SplitMode = SplitMode.ALL_MEMBERS,
    val selectedSplitMemberIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
