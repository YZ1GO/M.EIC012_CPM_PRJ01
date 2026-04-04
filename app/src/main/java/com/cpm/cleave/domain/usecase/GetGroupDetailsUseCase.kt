package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.Group
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.min

data class DebtReason(
    val expenseLabel: String,
    val amount: Double
)

data class DebtWithReason(
    val debt: Debt,
    val reasons: List<DebtReason>
)

data class GroupDetailsData(
    val group: Group,
    val expenses: List<Expense>,
    val debts: List<Debt>,
    val debtsWithReason: List<DebtWithReason>,
    val userDisplayNames: Map<String, String>,
    val userPhotoUrls: Map<String, String>,
    val userLastSeen: Map<String, Long>,
    val currentUserId: String?
)

class GetGroupDetailsUseCase(
    private val groupRepository: IGroupRepository,
    private val expenseRepository: IExpenseRepository,
    private val authRepository: IAuthRepository
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

        val sharesByExpenseId = expenseRepository.getExpenseSharesByGroup(groupId)
            .getOrElse { return Result.failure(it) }

        val debtsWithReason = buildDebtReasons(
            debts = debts,
            expenses = expenses,
            sharesByExpenseId = sharesByExpenseId
        )

        val currentUserId = authRepository.getCurrentUser().getOrNull()?.id
        val userDisplayNames = resolveUserDisplayNames(
            group = group,
            expenses = expenses,
            debts = debts,
            sharesByExpenseId = sharesByExpenseId,
            currentUserId = currentUserId
        )
        val userPhotoUrls = resolveUserPhotoUrls(
            group = group,
            expenses = expenses,
            debts = debts,
            sharesByExpenseId = sharesByExpenseId
        )
        val userLastSeen = resolveUserLastSeen(
            group = group,
            expenses = expenses,
            debts = debts,
            sharesByExpenseId = sharesByExpenseId
        )

        return Result.success(
            GroupDetailsData(
                group = group,
                expenses = expenses,
                debts = debts,
                debtsWithReason = debtsWithReason,
                userDisplayNames = userDisplayNames,
                userPhotoUrls = userPhotoUrls,
                userLastSeen = userLastSeen,
                currentUserId = currentUserId
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

                val sharesByExpenseId = expenseRepository.getExpenseSharesByGroup(groupId)
                    .getOrElse { return@map Result.failure(it) }

                val debtsWithReason = buildDebtReasons(
                    debts = debts,
                    expenses = observedExpenses,
                    sharesByExpenseId = sharesByExpenseId
                )

                val currentUserId = authRepository.getCurrentUser().getOrNull()?.id
                val userDisplayNames = resolveUserDisplayNames(
                    group = group,
                    expenses = observedExpenses,
                    debts = debts,
                    sharesByExpenseId = sharesByExpenseId,
                    currentUserId = currentUserId
                )
                val userPhotoUrls = resolveUserPhotoUrls(
                    group = group,
                    expenses = observedExpenses,
                    debts = debts,
                    sharesByExpenseId = sharesByExpenseId
                )
                val userLastSeen = resolveUserLastSeen(
                    group = group,
                    expenses = observedExpenses,
                    debts = debts,
                    sharesByExpenseId = sharesByExpenseId
                )

                Result.success(
                    GroupDetailsData(
                        group = group,
                        expenses = observedExpenses.sortedByDescending { it.date },
                        debts = debts,
                        debtsWithReason = debtsWithReason,
                        userDisplayNames = userDisplayNames,
                        userPhotoUrls = userPhotoUrls,
                        userLastSeen = userLastSeen,
                        currentUserId = currentUserId
                    )
                )
            }
    }

    private fun buildDebtReasons(
        debts: List<Debt>,
        expenses: List<Expense>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ): List<DebtWithReason> {
        val debtByUser = mutableMapOf<String, MutableList<Pair<String, Long>>>()

        expenses
            .sortedBy { it.date }
            .forEach { expense ->
                val label = expense.description.ifBlank { "Expense" }
                sharesByExpenseId[expense.id].orEmpty()
                    .forEach { share ->
                        val cents = toCents(share.amount)
                        if (cents <= 0L) return@forEach
                        debtByUser
                            .getOrPut(share.userId) { mutableListOf() }
                            .add(label to cents)
                    }
            }

        return debts.map { debt ->
            var remaining = toCents(debt.amount)
            val bucket = debtByUser[debt.fromUser].orEmpty().toMutableList()
            val grouped = linkedMapOf<String, Long>()
            var idx = 0

            while (remaining > 0L && idx < bucket.size) {
                val (label, available) = bucket[idx]
                if (available <= 0L) {
                    idx++
                    continue
                }

                val taken = min(remaining, available)
                grouped[label] = (grouped[label] ?: 0L) + taken
                remaining -= taken
                bucket[idx] = label to (available - taken)

                if (bucket[idx].second == 0L) idx++
            }

            debtByUser[debt.fromUser] = bucket

            val reasons = grouped.entries.map { (label, cents) ->
                DebtReason(
                    expenseLabel = label,
                    amount = cents / 100.0
                )
            }

            DebtWithReason(debt = debt, reasons = reasons)
        }
    }

    private suspend fun resolveUserDisplayNames(
        group: Group,
        expenses: List<Expense>,
        debts: List<Debt>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>,
        currentUserId: String?
    ): Map<String, String> {
        val ids = mutableSetOf<String>()
        ids.addAll(group.members)
        expenses.forEach { expense ->
            ids.add(expense.paidByUserId)
            expense.payerContributions.forEach { contribution -> ids.add(contribution.userId) }
            sharesByExpenseId[expense.id].orEmpty().forEach { share -> ids.add(share.userId) }
        }
        debts.forEach { debt ->
            ids.add(debt.fromUser)
            ids.add(debt.toUser)
        }

        return ids.filter { it.isNotBlank() }
            .associateWith { userId ->
                val normalizedUserId = normalizeUserId(userId)
                when {
                    currentUserId != null && normalizedUserId == normalizeUserId(currentUserId) -> "You"
                    else -> authRepository.getUserDisplayName(userId).getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: fallbackDisplayName(normalizedUserId)
                }
            }
    }

    private suspend fun resolveUserPhotoUrls(
        group: Group,
        expenses: List<Expense>,
        debts: List<Debt>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ): Map<String, String> {
        val ids = mutableSetOf<String>()
        ids.addAll(group.members)
        expenses.forEach { expense ->
            ids.add(expense.paidByUserId)
            expense.payerContributions.forEach { contribution -> ids.add(contribution.userId) }
            sharesByExpenseId[expense.id].orEmpty().forEach { share -> ids.add(share.userId) }
        }
        debts.forEach { debt ->
            ids.add(debt.fromUser)
            ids.add(debt.toUser)
        }

        return ids
            .filter { it.isNotBlank() }
            .mapNotNull { userId ->
                authRepository.getUserPhotoUrl(userId)
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url -> userId to url }
            }
            .toMap()
    }

    private suspend fun resolveUserLastSeen(
        group: Group,
        expenses: List<Expense>,
        debts: List<Debt>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ): Map<String, Long> {
        val ids = mutableSetOf<String>()
        ids.addAll(group.members)
        expenses.forEach { expense ->
            ids.add(expense.paidByUserId)
            expense.payerContributions.forEach { contribution -> ids.add(contribution.userId) }
            sharesByExpenseId[expense.id].orEmpty().forEach { share -> ids.add(share.userId) }
        }
        debts.forEach { debt ->
            ids.add(debt.fromUser)
            ids.add(debt.toUser)
        }

        return ids
            .filter { it.isNotBlank() }
            .mapNotNull { userId ->
                authRepository.getUserLastSeen(userId)
                    .getOrNull()
                    ?.let { lastSeen -> userId to lastSeen }
            }
            .toMap()
    }

    private fun toCents(amount: Double): Long {
        return kotlin.math.round(amount * 100.0).toLong()
    }

    private fun normalizeUserId(rawUserId: String): String {
        val trimmed = rawUserId.trim()
        if (trimmed.isBlank()) return ""
        return trimmed.substringAfterLast('/')
    }

    private fun fallbackDisplayName(normalizedUserId: String): String {
        if (normalizedUserId.contains("guest", ignoreCase = true) ||
            normalizedUserId.contains("anon", ignoreCase = true)
        ) {
            return "Guest"
        }
        return "User"
    }
}
