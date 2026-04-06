package com.cpm.cleave.domain

import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.PayerContribution

class DebtCalculator {
    fun calculateDebts(
        groupMembers: List<String>,
        expenses: List<Expense>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ): List<Debt> {
        if (groupMembers.isEmpty() || expenses.isEmpty()) return emptyList()

        val balances = groupMembers.associateWith { 0L }.toMutableMap()

        expenses.forEach { expense ->
            val payerContributions = expense.payerContributions.ifEmpty {
                listOf(PayerContribution(userId = expense.paidByUserId, amount = expense.amount))
            }
            payerContributions.forEach { contribution ->
                balances[contribution.userId] = (balances[contribution.userId] ?: 0L) + toCents(contribution.amount)
            }

            val splits = sharesByExpenseId[expense.id].orEmpty()
            splits.forEach { split ->
                balances[split.userId] = (balances[split.userId] ?: 0L) - toCents(split.amount)
            }
        }

        val creditors = balances
            .filterValues { it > 0L }
            .map { it.key to it.value }
            .toMutableList()
        val debtors = balances
            .filterValues { it < 0L }
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
            if (transfer > 0L) {
                debts.add(
                    Debt(
                        fromUser = debtorId,
                        toUser = creditorId,
                        amount = transfer / 100.0
                    )
                )
            }

            val remainingCreditor = creditorAmount - transfer
            val remainingDebtor = debtorAmount - transfer

            creditors[creditorIndex] = creditorId to remainingCreditor
            debtors[debtorIndex] = debtorId to remainingDebtor

            if (remainingCreditor <= 0L) creditorIndex++
            if (remainingDebtor <= 0L) debtorIndex++
        }

        return debts
    }

    private fun toCents(value: Double): Long {
        return kotlin.math.round(value * 100.0).toLong()
    }
}
