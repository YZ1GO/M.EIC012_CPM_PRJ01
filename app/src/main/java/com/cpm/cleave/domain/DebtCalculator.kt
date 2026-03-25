package com.cpm.cleave.domain

import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.PayerContribution
import kotlin.math.round

class DebtCalculator {
    companion object {
        // Treat values below one cent as zero to avoid floating-point residue in debt math.
        private const val BALANCE_EPSILON = 0.009
    }

    fun calculateDebts(
        groupMembers: List<String>,
        expenses: List<Expense>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ): List<Debt> {
        if (groupMembers.isEmpty() || expenses.isEmpty()) return emptyList()

        val balances = groupMembers.associateWith { 0.0 }.toMutableMap()

        expenses.forEach { expense ->
            val payerContributions = expense.payerContributions.ifEmpty {
                listOf(PayerContribution(userId = expense.paidByUserId, amount = expense.amount))
            }
            payerContributions.forEach { contribution ->
                balances[contribution.userId] = (balances[contribution.userId] ?: 0.0) + contribution.amount
            }

            val splits = sharesByExpenseId[expense.id].orEmpty()
            splits.forEach { split ->
                balances[split.userId] = (balances[split.userId] ?: 0.0) - split.amount
            }
        }

        val creditors = balances
            .filterValues { it > BALANCE_EPSILON }
            .map { it.key to it.value }
            .toMutableList()
        val debtors = balances
            .filterValues { it < -BALANCE_EPSILON }
            .map { it.key to -it.value }
            .toMutableList()

        val debts = mutableListOf<Debt>()
        var creditorIndex = 0
        var debtorIndex = 0

        // Baseline settlement strategy. A later optimization pass can simplify transitive chains.
        while (creditorIndex < creditors.size && debtorIndex < debtors.size) {
            val (creditorId, creditorAmount) = creditors[creditorIndex]
            val (debtorId, debtorAmount) = debtors[debtorIndex]

            val transfer = minOf(creditorAmount, debtorAmount)
            if (transfer > BALANCE_EPSILON) {
                debts.add(
                    Debt(
                        fromUser = debtorId,
                        toUser = creditorId,
                        amount = roundToTwoDecimals(transfer)
                    )
                )
            }

            val remainingCreditor = creditorAmount - transfer
            val remainingDebtor = debtorAmount - transfer

            creditors[creditorIndex] = creditorId to remainingCreditor
            debtors[debtorIndex] = debtorId to remainingDebtor

            if (remainingCreditor <= BALANCE_EPSILON) creditorIndex++
            if (remainingDebtor <= BALANCE_EPSILON) debtorIndex++
        }

        return debts
    }

    private fun roundToTwoDecimals(value: Double): Double {
        return round(value * 100.0) / 100.0
    }
}
