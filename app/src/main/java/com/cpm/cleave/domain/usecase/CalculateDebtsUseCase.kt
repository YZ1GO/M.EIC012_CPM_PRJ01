package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.DebtCalculator
import com.cpm.cleave.domain.DebtSettlementOptimizer
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ExpenseShare

class CalculateDebtsUseCase(
    private val debtCalculator: DebtCalculator = DebtCalculator(),
    private val debtSettlementOptimizer: DebtSettlementOptimizer = DebtSettlementOptimizer(),
    private val enableOptimizedSettlement: Boolean = true
) {
    fun execute(
        groupMembers: List<String>,
        expenses: List<Expense>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ): List<Debt> {
        val baselineDebts = debtCalculator.calculateDebts(
            groupMembers = groupMembers,
            expenses = expenses,
            sharesByExpenseId = sharesByExpenseId
        )

        if (!enableOptimizedSettlement) return baselineDebts

        return runCatching {
            debtSettlementOptimizer.optimize(baselineDebts)
        }.getOrElse {
            baselineDebts
        }
    }
}
