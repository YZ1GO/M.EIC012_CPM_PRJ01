package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.usecase.CalculateDebtsUseCase
import com.cpm.cleave.domain.usecase.CreateExpenseCommand
import com.cpm.cleave.domain.usecase.CreateExpenseUseCase
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import java.util.UUID

class ExpenseRepositoryImpl(
    private val cache: Cache,
    private val authSessionStore: AuthSessionStore,
    private val calculateDebtsUseCase: CalculateDebtsUseCase = CalculateDebtsUseCase(),
    private val createExpenseUseCase: CreateExpenseUseCase = CreateExpenseUseCase()
) : IExpenseRepository {

    override suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>> {
        return try {
            Result.success(cache.getExpensesByGroup(groupId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDebtsByGroup(groupId: String): Result<List<Debt>> {
        return try {
            val group = cache.getGroupById(groupId) ?: return Result.success(emptyList())
            val expenses = cache.getExpensesByGroup(groupId)
            val sharesByExpenseId = expenses.associate { expense ->
                expense.id to cache.getExpenseSharesForExpense(expense.id)
            }

            Result.success(
                calculateDebtsUseCase.execute(
                    groupMembers = group.members,
                    expenses = expenses,
                    sharesByExpenseId = sharesByExpenseId
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createExpense(
        groupId: String,
        amount: Double,
        description: String,
        paidByUserId: String,
        splitMemberIds: List<String>
    ): Result<Unit> {
        // TODO(expense-advanced): support multi-payer expenses by accepting payer contributions and validating sum == total.
        return try {
            val currentUser = authSessionStore.getActiveUser()
            val group = cache.getGroupById(groupId)

            val command = CreateExpenseCommand(
                amount = amount,
                paidByUserId = paidByUserId,
                splitMemberIds = splitMemberIds
            )
            createExpenseUseCase.execute(
                command = command,
                currentUser = currentUser,
                group = group
            ).getOrElse { return Result.failure(it) }

            cache.insertExpenseWithSplit(
                expenseId = UUID.randomUUID().toString(),
                amount = amount,
                description = description,
                date = System.currentTimeMillis(),
                groupId = groupId,
                paidBy = paidByUserId,
                memberIds = splitMemberIds
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
