package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    fun observe(groupId: String): Flow<Result<GroupDetailsData>> {
        val groupsFlow = groupRepository.observeGroups()
        val expensesFlow = expenseRepository.observeExpensesByGroup(groupId)

        return combine(groupsFlow, expensesFlow) { groups, observedExpenses ->
            groups to observedExpenses
        }
            .map { (groups, observedExpenses) ->
                val group = groups.firstOrNull { it.id == groupId }
                    ?: return@map Result.failure(IllegalArgumentException("Group not found."))

                val debts = expenseRepository.getDebtsByGroup(groupId)
                    .getOrElse { return@map Result.failure(it) }

                Result.success(
                    GroupDetailsData(
                        group = group,
                        expenses = observedExpenses.sortedByDescending { it.date },
                        debts = debts
                    )
                )
            }
    }
}
