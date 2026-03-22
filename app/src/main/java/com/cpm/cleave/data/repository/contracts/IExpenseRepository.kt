package com.cpm.cleave.data.repository.contracts

import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Debt

interface IExpenseRepository {
    suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>>
    suspend fun getDebtsByGroup(groupId: String): Result<List<Debt>>
    suspend fun createExpense(
        groupId: String,
        amount: Double,
        description: String,
        paidByUserId: String,
        splitMemberIds: List<String>
    ): Result<Unit>
}
