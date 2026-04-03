package com.cpm.cleave.ui.features.groups

import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group
import com.cpm.cleave.domain.usecase.DebtWithReason

data class GroupDetailsUiState(
    val isLoading: Boolean = true,
    val group: Group? = null,
    val expenses: List<Expense> = emptyList(),
    val debts: List<Debt> = emptyList(),
    val debtsWithReason: List<DebtWithReason> = emptyList(),
    val userDisplayNames: Map<String, String> = emptyMap(),
    val userPhotoUrls: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)
