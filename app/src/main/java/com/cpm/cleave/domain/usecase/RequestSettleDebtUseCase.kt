package com.cpm.cleave.domain.usecase

import com.cpm.cleave.model.Debt

class RequestSettleDebtUseCase(
    private val requestCreateExpenseUseCase: RequestCreateExpenseUseCase
) {
    suspend fun execute(
        groupId: String,
        debt: Debt,
        amountToPay: Double
    ): Result<Unit> {
        val normalizedAmount = amountToPay
            .coerceAtLeast(0.0)

        if (normalizedAmount <= 0.0) {
            return Result.failure(IllegalArgumentException("Payment amount must be greater than zero."))
        }

        val description = "PAYMENT: ${debt.fromUser} -> ${debt.toUser}"

        return requestCreateExpenseUseCase.execute(
            groupId = groupId,
            amount = normalizedAmount,
            description = description,
            splitMemberIds = listOf(debt.toUser),
            payerContributions = mapOf(debt.fromUser to normalizedAmount)
        )
    }
}
