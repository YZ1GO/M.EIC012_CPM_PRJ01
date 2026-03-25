package com.cpm.cleave.domain

import com.cpm.cleave.model.Debt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtSettlementOptimizerTest {

    private val optimizer = DebtSettlementOptimizer()

    @Test
    fun optimize_reducesTransitiveChain() {
        val input = listOf(
            Debt(fromUser = "A", toUser = "B", amount = 10.0),
            Debt(fromUser = "B", toUser = "C", amount = 10.0)
        )

        val optimized = optimizer.optimize(input)

        assertEquals(1, optimized.size)
        assertEquals("A", optimized[0].fromUser)
        assertEquals("C", optimized[0].toUser)
        assertEquals(10.0, optimized[0].amount, 0.0001)
    }

    @Test
    fun optimize_preservesNetBalancePerUser() {
        val input = listOf(
            Debt(fromUser = "u1", toUser = "u2", amount = 7.25),
            Debt(fromUser = "u2", toUser = "u3", amount = 2.10),
            Debt(fromUser = "u1", toUser = "u3", amount = 1.35),
            Debt(fromUser = "u4", toUser = "u2", amount = 4.00)
        )

        val optimized = optimizer.optimize(input)

        val before = netByUser(input)
        val after = netByUser(optimized)
        assertEquals(before, after)
        assertTrue(optimized.size <= input.size)
    }

    private fun netByUser(debts: List<Debt>): Map<String, Long> {
        val balances = mutableMapOf<String, Long>()
        debts.forEach { debt ->
            val cents = kotlin.math.round(debt.amount * 100.0).toLong()
            balances[debt.fromUser] = (balances[debt.fromUser] ?: 0L) - cents
            balances[debt.toUser] = (balances[debt.toUser] ?: 0L) + cents
        }
        return balances.toSortedMap()
    }
}
