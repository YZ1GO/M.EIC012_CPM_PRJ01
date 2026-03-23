package com.cpm.cleave.domain.repository.contracts

import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Debt
import kotlinx.coroutines.flow.Flow

interface IExpenseRepository {
    suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>>
    fun observeExpensesByGroup(groupId: String): Flow<List<Expense>>
    suspend fun getDebtsByGroup(groupId: String): Result<List<Debt>>
    suspend fun createExpense(
        groupId: String,
        amount: Double,
        description: String,
        paidByUserId: String,
        splitMemberIds: List<String>
    ): Result<Unit>
}
