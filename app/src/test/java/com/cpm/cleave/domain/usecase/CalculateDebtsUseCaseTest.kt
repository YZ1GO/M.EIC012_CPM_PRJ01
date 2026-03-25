package com.cpm.cleave.domain.usecase

import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.PayerContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculateDebtsUseCaseTest {

    @Test
    fun execute_handlesMultiBuyerBalances() {
        val useCase = CalculateDebtsUseCase(enableOptimizedSettlement = true)

        val expenses = listOf(
            Expense(
                id = "e1",
                amount = 120.0,
                description = "Dinner",
                date = 1L,
                groupId = "g1",
                paidByUserId = "ana",
                payerContributions = listOf(
                    PayerContribution("ana", 70.0),
                    PayerContribution("bruno", 50.0)
                )
            )
        )

        val sharesByExpenseId = mapOf(
            "e1" to listOf(
                ExpenseShare("ana", 30.0),
                ExpenseShare("bruno", 30.0),
                ExpenseShare("carla", 30.0),
                ExpenseShare("diogo", 30.0)
            )
        )

        val debts = useCase.execute(
            groupMembers = listOf("ana", "bruno", "carla", "diogo"),
            expenses = expenses,
            sharesByExpenseId = sharesByExpenseId
        )

        // Expected net: ana +40, bruno +20, carla -30, diogo -30.
        val totals = mutableMapOf<String, Double>()
        debts.forEach { debt ->
            totals[debt.fromUser] = (totals[debt.fromUser] ?: 0.0) - debt.amount
            totals[debt.toUser] = (totals[debt.toUser] ?: 0.0) + debt.amount
        }

        assertEquals(40.0, totals["ana"] ?: 0.0, 0.01)
        assertEquals(20.0, totals["bruno"] ?: 0.0, 0.01)
        assertEquals(-30.0, totals["carla"] ?: 0.0, 0.01)
        assertEquals(-30.0, totals["diogo"] ?: 0.0, 0.01)
    }

    @Test
    fun execute_withOptimizationEnabled_hasNoMoreTransfersThanBaseline() {
        val optimizedUseCase = CalculateDebtsUseCase(enableOptimizedSettlement = true)
        val baselineUseCase = CalculateDebtsUseCase(enableOptimizedSettlement = false)

        val expenses = listOf(
            Expense(
                id = "e1",
                amount = 60.0,
                description = "E1",
                date = 1L,
                groupId = "g1",
                paidByUserId = "u1",
                payerContributions = listOf(PayerContribution("u1", 60.0))
            ),
            Expense(
                id = "e2",
                amount = 40.0,
                description = "E2",
                date = 2L,
                groupId = "g1",
                paidByUserId = "u2",
                payerContributions = listOf(PayerContribution("u2", 40.0))
            )
        )

        val shares = mapOf(
            "e1" to listOf(
                ExpenseShare("u1", 20.0),
                ExpenseShare("u2", 20.0),
                ExpenseShare("u3", 20.0)
            ),
            "e2" to listOf(
                ExpenseShare("u1", 20.0),
                ExpenseShare("u2", 20.0)
            )
        )

        val baseline = baselineUseCase.execute(
            groupMembers = listOf("u1", "u2", "u3"),
            expenses = expenses,
            sharesByExpenseId = shares
        )
        val optimized = optimizedUseCase.execute(
            groupMembers = listOf("u1", "u2", "u3"),
            expenses = expenses,
            sharesByExpenseId = shares
        )

        assertTrue(optimized.size <= baseline.size)
        assertEquals(netByUser(baseline), netByUser(optimized))
    }

    private fun netByUser(debts: List<com.cpm.cleave.model.Debt>): Map<String, Long> {
        val balances = mutableMapOf<String, Long>()
        debts.forEach { debt ->
            val cents = kotlin.math.round(debt.amount * 100.0).toLong()
            balances[debt.fromUser] = (balances[debt.fromUser] ?: 0L) - cents
            balances[debt.toUser] = (balances[debt.toUser] ?: 0L) + cents
        }
        return balances.toSortedMap()
    }
}
