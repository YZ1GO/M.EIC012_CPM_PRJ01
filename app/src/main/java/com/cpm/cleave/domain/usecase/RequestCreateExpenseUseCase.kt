package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository

class RequestCreateExpenseUseCase(
    private val expenseRepository: IExpenseRepository
) {
    suspend fun execute(
        groupId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        receiptImageBytes: ByteArray? = null
    ): Result<Unit> {
        return expenseRepository.createExpense(
            groupId = groupId,
            amount = amount,
            description = description,
            splitMemberIds = splitMemberIds,
            payerContributions = payerContributions,
            receiptImageBytes = receiptImageBytes
        )
    }
}
