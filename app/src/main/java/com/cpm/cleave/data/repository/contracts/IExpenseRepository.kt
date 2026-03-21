package com.cpm.cleave.data.repository.contracts

import com.cpm.cleave.model.Expense

interface IExpenseRepository {
    suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>>
    suspend fun createExpense(
        groupId: String,
        amount: Double,
        description: String,
        paidByUserId: String,
        splitMemberIds: List<String>
    ): Result<Unit>
}
