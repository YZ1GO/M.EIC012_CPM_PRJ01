package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.DebtCalculator
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ExpenseShare

class CalculateDebtsUseCase(
    private val debtCalculator: DebtCalculator = DebtCalculator()
) {
    fun execute(
        groupMembers: List<String>,
        expenses: List<Expense>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ): List<Debt> {
        return debtCalculator.calculateDebts(
            groupMembers = groupMembers,
            expenses = expenses,
            sharesByExpenseId = sharesByExpenseId
        )
    }
}
