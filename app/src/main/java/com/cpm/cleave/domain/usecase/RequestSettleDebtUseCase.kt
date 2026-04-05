package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.model.Debt
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class RequestSettleDebtUseCase(
    private val requestCreateExpenseUseCase: RequestCreateExpenseUseCase,
    private val authRepository: IAuthRepository
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

        val fromName = resolveDisplayName(debt.fromUser)
        val toName = resolveDisplayName(debt.toUser)
        val description = "PAYMENT:\n$fromName -> $toName"

        return requestCreateExpenseUseCase.execute(
            groupId = groupId,
            amount = normalizedAmount,
            description = description,
            splitMemberIds = listOf(debt.toUser),
            payerContributions = mapOf(debt.fromUser to normalizedAmount)
        )
    }

    private suspend fun resolveDisplayName(userId: String): String {
        val resolved = withTimeoutOrNull(200L) {
            authRepository.getUserDisplayName(userId)
                .getOrNull()
                ?.trim()
                .orEmpty()
        }.orEmpty()
        if (resolved.isNotBlank()) return resolved

        val normalizedId = userId.trim().substringAfterLast('/')
        if (normalizedId.contains("guest", ignoreCase = true) || normalizedId.contains("anon", ignoreCase = true)) {
            val suffix = normalizedId.takeLast(4).uppercase(Locale.ROOT).ifBlank { "USER" }
            return "Guest-$suffix"
        }
        return "User"
    }
}
