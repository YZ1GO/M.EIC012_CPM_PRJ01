package com.cpm.cleave.domain.usecase

import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User

data class CreateExpenseCommand(
    val amount: Double,
    val payerContributions: Map<String, Double>,
    val splitMemberIds: List<String>
)

class CreateExpenseUseCase {
    fun execute(
        command: CreateExpenseCommand,
        currentUser: User?,
        group: Group?
    ): Result<Unit> {
        if (command.amount <= 0.0) {
            return Result.failure(IllegalArgumentException("Expense amount must be greater than zero."))
        }

        if (command.splitMemberIds.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one member to split with."))
        }

        if (currentUser == null) {
            return Result.failure(IllegalStateException("No current user found."))
        }

        if (group == null) {
            return Result.failure(IllegalArgumentException("Group not found."))
        }

        if (!group.members.contains(currentUser.id)) {
            return Result.failure(IllegalStateException("Current user is not a member of this group."))
        }

        if (command.payerContributions.isEmpty()) {
            return Result.failure(IllegalArgumentException("Add at least one payer contribution."))
        }

        if (command.payerContributions.any { it.value <= 0.0 }) {
            return Result.failure(IllegalArgumentException("Payer contributions must be greater than zero."))
        }

        if (!command.payerContributions.keys.all { group.members.contains(it) }) {
            return Result.failure(IllegalArgumentException("All payers must belong to the group."))
        }

        val payerTotal = command.payerContributions.values.sum()
        if (kotlin.math.abs(payerTotal - command.amount) > 0.009) {
            return Result.failure(IllegalArgumentException("Payer contributions must equal the expense amount."))
        }

        if (!command.splitMemberIds.all { group.members.contains(it) }) {
            return Result.failure(IllegalArgumentException("All split members must belong to the group."))
        }

        return Result.success(Unit)
    }
}
