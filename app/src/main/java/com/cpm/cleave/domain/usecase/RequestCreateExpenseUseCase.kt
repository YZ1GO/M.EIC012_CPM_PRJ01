package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository

class RequestCreateExpenseUseCase(
    private val expenseRepository: IExpenseRepository
) {
    suspend fun execute(
        groupId: String,
        amount: Double,
        description: String,
        paidByUserId: String,
        splitMemberIds: List<String>
    ): Result<Unit> {
        return expenseRepository.createExpense(
            groupId = groupId,
            amount = amount,
            description = description,
            paidByUserId = paidByUserId,
            splitMemberIds = splitMemberIds
        )
    }
}
