package com.cpm.cleave.ui.features.groups

import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group
import com.cpm.cleave.domain.usecase.DebtWithReason

data class GroupDetailsUiState(
    val isLoading: Boolean = true,
    val currentUserId: String? = null,
    val group: Group? = null,
    val expenses: List<Expense> = emptyList(),
    val debts: List<Debt> = emptyList(),
    val debtsWithReason: List<DebtWithReason> = emptyList(),
    val totalYouOwe: Double = 0.0,
    val totalOwedToYou: Double = 0.0,
    val userDisplayNames: Map<String, String> = emptyMap(),
    val userPhotoUrls: Map<String, String> = emptyMap(),
    val userLastSeen: Map<String, Long> = emptyMap(),
    val selectedMemberForProfileId: String? = null,
    val isEditingGroup: Boolean = false,
    val editedGroupName: String = "",
    val editedCurrencyCode: String = "EUR",
    val isUpdatingGroup: Boolean = false,
    val selectedDebtForPayment: Debt? = null,
    val debtPaymentAmountInput: String = "",
    val isSettlingDebt: Boolean = false,
    val selectedExpenseForDeletionId: String? = null,
    val isDeletingExpense: Boolean = false,
    val selectedMemberForExpulsionId: String? = null,
    val isExpellingMember: Boolean = false,
    val canDeleteGroup: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null
)
