package com.cpm.cleave.domain.repository.contracts

import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.ReceiptItem
import kotlinx.coroutines.flow.Flow

interface IExpenseRepository {
    suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>>
    suspend fun getExpenseSharesByGroup(groupId: String): Result<Map<String, List<ExpenseShare>>>
    fun observeExpensesByGroup(groupId: String): Flow<List<Expense>>
    fun observeDebtsByGroup(groupId: String): Flow<List<Debt>>
    suspend fun getDebtsByGroup(groupId: String): Result<List<Debt>>
    suspend fun deleteExpense(groupId: String, expenseId: String): Result<Unit>
    suspend fun updateExpense(
        groupId: String,
        expenseId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        receiptImageBytes: ByteArray? = null,
        receiptItems: List<ReceiptItem> = emptyList()
    ): Result<Unit>
    suspend fun createExpense(
        groupId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        receiptImageBytes: ByteArray? = null,
        receiptItems: List<ReceiptItem> = emptyList()
    ): Result<Unit>
}
