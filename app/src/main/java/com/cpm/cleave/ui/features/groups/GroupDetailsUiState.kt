package com.cpm.cleave.ui.features.groups

import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group

data class GroupDetailsUiState(
    val isLoading: Boolean = true,
    val group: Group? = null,
    val expenses: List<Expense> = emptyList(),
    val debts: List<Debt> = emptyList(),
    val errorMessage: String? = null
)
