package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group

data class GroupDetailsData(
    val group: Group,
    val expenses: List<Expense>,
    val debts: List<Debt>
)

class GetGroupDetailsUseCase(
    private val groupRepository: IGroupRepository,
    private val expenseRepository: IExpenseRepository
) {
    suspend fun execute(groupId: String): Result<GroupDetailsData> {
        val group = groupRepository.getGroupById(groupId)
            .getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalArgumentException("Group not found."))

        val expenses = expenseRepository.getExpensesByGroup(groupId)
            .getOrElse { return Result.failure(it) }
            .sortedByDescending { it.date }

        val debts = expenseRepository.getDebtsByGroup(groupId)
            .getOrElse { return Result.failure(it) }

        return Result.success(
            GroupDetailsData(
                group = group,
                expenses = expenses,
                debts = debts
            )
        )
    }
}
