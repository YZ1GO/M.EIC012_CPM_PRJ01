package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.model.ReceiptItem

class RequestCreateExpenseUseCase(
    private val expenseRepository: IExpenseRepository
) {
    suspend fun execute(
        groupId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        receiptImageBytes: ByteArray? = null,
        receiptItems: List<ReceiptItem> = emptyList()
    ): Result<Unit> {
        return expenseRepository.createExpense(
            groupId = groupId,
            amount = amount,
            description = description,
            splitMemberIds = splitMemberIds,
            payerContributions = payerContributions,
            receiptImageBytes = receiptImageBytes,
            receiptItems = receiptItems
        )
    }
}
