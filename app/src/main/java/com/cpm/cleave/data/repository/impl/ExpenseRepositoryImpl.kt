package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.repository.contracts.IExpenseRepository
import com.cpm.cleave.model.Expense
import java.util.UUID

class ExpenseRepositoryImpl(
    private val cache: Cache
) : IExpenseRepository {

    override suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>> {
        return try {
            Result.success(cache.getExpensesByGroup(groupId))
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
            if (amount <= 0.0) {
                return Result.failure(IllegalArgumentException("Expense amount must be greater than zero."))
            }

            if (splitMemberIds.isEmpty()) {
                return Result.failure(IllegalArgumentException("Select at least one member to split with."))
            }

            val currentUser = cache.getActiveAnonymousUser()
                ?: return Result.failure(IllegalStateException("No current user found."))
            val group = cache.getGroupById(groupId)
                ?: return Result.failure(IllegalArgumentException("Group not found."))

            if (!group.members.contains(currentUser.id)) {
                return Result.failure(IllegalStateException("Current user is not a member of this group."))
            }

            if (!group.members.contains(paidByUserId)) {
                return Result.failure(IllegalArgumentException("Selected payer is not a member of this group."))
            }

            if (!splitMemberIds.all { group.members.contains(it) }) {
                return Result.failure(IllegalArgumentException("All split members must belong to the group."))
            }

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
