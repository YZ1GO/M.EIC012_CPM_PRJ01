package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.model.Expense

data class EditableExpenseData(
    val expense: Expense,
    val splitMemberIds: Set<String>
)

class GetEditableExpenseUseCase(
    private val expenseRepository: IExpenseRepository
) {
    suspend fun execute(groupId: String, expenseId: String): Result<EditableExpenseData> {
        val expenses = expenseRepository.getExpensesByGroup(groupId)
            .getOrElse { return Result.failure(it) }
        val expense = expenses.firstOrNull { it.id == expenseId }
            ?: return Result.failure(IllegalArgumentException("Expense not found"))

        val sharesByExpenseId = expenseRepository.getExpenseSharesByGroup(groupId)
            .getOrElse { return Result.failure(it) }

        val splitMemberIds = sharesByExpenseId[expenseId]
            .orEmpty()
            .map { it.userId }
            .toSet()

        return Result.success(
            EditableExpenseData(
                expense = expense,
                splitMemberIds = splitMemberIds
            )
        )
    }
}
