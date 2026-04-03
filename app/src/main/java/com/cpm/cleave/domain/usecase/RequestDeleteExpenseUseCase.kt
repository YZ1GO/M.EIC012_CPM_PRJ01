package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository

class RequestDeleteExpenseUseCase(
    private val expenseRepository: IExpenseRepository
) {
    suspend fun execute(groupId: String, expenseId: String): Result<Unit> {
        return expenseRepository.deleteExpense(groupId = groupId, expenseId = expenseId)
    }
}
