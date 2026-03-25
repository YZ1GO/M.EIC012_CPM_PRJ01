package com.cpm.cleave.domain.usecase

import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateExpenseUseCaseTest {

    private val useCase = CreateExpenseUseCase()

    private val userA = User(
        id = "u1",
        name = "User 1",
        email = null,
        isAnonymous = true,
        isDeleted = false,
        lastSeen = 0L,
        groups = listOf("g1")
    )

    private val group = Group(
        id = "g1",
        name = "Group",
        currency = "EUR",
        members = listOf("u1", "u2", "u3"),
        joinCode = "JOIN1",
        balances = emptyMap()
    )

    @Test
    fun execute_failsWhenPayerSumDiffersFromAmount() {
        val command = CreateExpenseCommand(
            amount = 100.0,
            payerContributions = mapOf("u1" to 30.0, "u2" to 50.0),
            splitMemberIds = listOf("u1", "u2", "u3")
        )

        val result = useCase.execute(command, userA, group)

        assertTrue(result.isFailure)
        assertEquals(
            "Payer contributions must equal the expense amount.",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun execute_failsWhenAnyPayerNotInGroup() {
        val command = CreateExpenseCommand(
            amount = 100.0,
            payerContributions = mapOf("u1" to 70.0, "uX" to 30.0),
            splitMemberIds = listOf("u1", "u2", "u3")
        )

        val result = useCase.execute(command, userA, group)

        assertTrue(result.isFailure)
        assertEquals(
            "All payers must belong to the group.",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun execute_failsWhenAnySplitMemberNotInGroup() {
        val command = CreateExpenseCommand(
            amount = 100.0,
            payerContributions = mapOf("u1" to 100.0),
            splitMemberIds = listOf("u1", "u2", "uX")
        )

        val result = useCase.execute(command, userA, group)

        assertTrue(result.isFailure)
        assertEquals(
            "All split members must belong to the group.",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun execute_succeedsForValidMultiBuyerExpense() {
        val command = CreateExpenseCommand(
            amount = 120.0,
            payerContributions = mapOf("u1" to 70.0, "u2" to 50.0),
            splitMemberIds = listOf("u1", "u2", "u3")
        )

        val result = useCase.execute(command, userA, group)

        assertTrue(result.isSuccess)
    }
}
