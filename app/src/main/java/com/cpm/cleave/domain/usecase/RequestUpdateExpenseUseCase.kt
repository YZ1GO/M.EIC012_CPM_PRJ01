package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.model.ReceiptItem

class RequestUpdateExpenseUseCase(
    private val expenseRepository: IExpenseRepository
) {
    suspend fun execute(
        groupId: String,
        expenseId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        receiptImageBytes: ByteArray? = null,
        removeReceiptImage: Boolean = false,
        receiptItems: List<ReceiptItem> = emptyList()
    ): Result<Unit> {
        return expenseRepository.updateExpense(
            groupId = groupId,
            expenseId = expenseId,
            amount = amount,
            description = description,
            splitMemberIds = splitMemberIds,
            payerContributions = payerContributions,
            receiptImageBytes = receiptImageBytes,
            removeReceiptImage = removeReceiptImage,
            receiptItems = receiptItems
        )
    }
}
