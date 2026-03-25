package com.cpm.cleave.data.local

import android.content.Context
import androidx.room.withTransaction
import com.cpm.cleave.data.local.entities.GroupEntity
import com.cpm.cleave.data.local.entities.GroupMemberEntity
import com.cpm.cleave.data.local.entities.ExpenseEntity
import com.cpm.cleave.data.local.entities.ExpensePayerEntity
import com.cpm.cleave.data.local.entities.ExpenseSplitEntity
import com.cpm.cleave.data.local.entities.UserEntity
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.PayerContribution
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.Group

class Cache(context: Context) {
    private val database = CleaveDatabase.getDatabase(context)
    private val groupDao = database.groupDao()
    private val groupMemberDao = database.groupMemberDao()
    private val expenseDao = database.expenseDao()
    private val expensePayerDao = database.expensePayerDao()
    private val expenseSplitDao = database.expenseSplitDao()
    private val userDao = database.userDao()

    suspend fun saveGroups(groups: List<Group>) {
        groups.forEach { group ->
            val groupEntity = GroupEntity(
                id = group.id,
                name = group.name,
                currency = group.currency,
                joinCode = group.joinCode
            )
            groupDao.insertGroup(groupEntity)
        }
    }

    suspend fun loadGroups(userId: String): List<Group> {
        val membershipRows = groupMemberDao.getGroupsOfUser(userId)

        return membershipRows.mapNotNull { membership ->
            val entity = groupDao.getGroupById(membership.groupId) ?: return@mapNotNull null
            val members = groupMemberDao.getMembersOfGroup(entity.id)
                .map { it.userId }
            Group(
                id = entity.id,
                name = entity.name,
                currency = entity.currency,
                members = members,
                joinCode = entity.joinCode,
                // Balances are derived from calculated debts in the group details flow.
                balances = emptyMap()
            )
        }
    }

    suspend fun getGroupById(groupId: String): Group? {
        val groupEntity = groupDao.getGroupById(groupId) ?: return null
        val members = groupMemberDao.getMembersOfGroup(groupId)
            .map { it.userId }
        return Group(
            id = groupEntity.id,
            name = groupEntity.name,
            currency = groupEntity.currency,
            members = members,
            joinCode = groupEntity.joinCode,
            balances = emptyMap()
        )
    }

    suspend fun getGroupByJoinCode(joinCode: String): Group? {
        val groupEntity = groupDao.getGroupByJoinCode(joinCode) ?: return null
        val members = groupMemberDao.getMembersOfGroup(groupEntity.id)
            .map { it.userId }
        return Group(
            id = groupEntity.id,
            name = groupEntity.name,
            currency = groupEntity.currency,
            members = members,
            joinCode = groupEntity.joinCode,
            balances = emptyMap()
        )
    }

    suspend fun insertGroup(group: Group) {
        val groupEntity = GroupEntity(
            id = group.id,
            name = group.name,
            currency = group.currency,
            joinCode = group.joinCode
        )
        groupDao.insertGroup(groupEntity)
    }

    suspend fun deleteGroupById(groupId: String) {
        val entity = groupDao.getGroupById(groupId) ?: return
        groupDao.deleteGroup(entity)
    }

    suspend fun upsertGroupWithMembers(group: Group) {
        database.withTransaction {
            groupDao.insertGroup(
                GroupEntity(
                    id = group.id,
                    name = group.name,
                    currency = group.currency,
                    joinCode = group.joinCode
                )
            )

            val incomingMembers = group.members.toSet()
            val existingMembers = groupMemberDao.getMembersOfGroup(group.id)

            existingMembers
                .filter { it.userId !in incomingMembers }
                .forEach { staleMember ->
                    groupMemberDao.removeMember(staleMember)
                }

            incomingMembers.forEach { memberId ->
                ensureUserExists(memberId)
                groupMemberDao.addMember(
                    GroupMemberEntity(
                        groupId = group.id,
                        userId = memberId
                    )
                )
            }
        }
    }

    suspend fun insertExpenseWithSplit(
        expenseId: String,
        amount: Double,
        description: String,
        date: Long,
        groupId: String,
        payerContributions: Map<String, Double>,
        memberIds: List<String>
    ) {
        database.withTransaction {
            if (payerContributions.isEmpty()) return@withTransaction
            val primaryPayer = payerContributions.maxByOrNull { it.value }?.key ?: return@withTransaction
            ensureUserExists(primaryPayer)
            val expense = ExpenseEntity(
                id = expenseId,
                amount = amount,
                description = description,
                date = date,
                groupId = groupId,
                paidBy = primaryPayer
            )
            expenseDao.insertExpense(expense)
            expensePayerDao.deletePayersForExpense(expenseId)
            payerContributions.forEach { (userId, paidAmount) ->
                ensureUserExists(userId)
                expensePayerDao.insertPayer(
                    ExpensePayerEntity(
                        expenseId = expenseId,
                        userId = userId,
                        amount = paidAmount
                    )
                )
            }
            expenseSplitDao.deleteSplitsForExpense(expenseId)

            if (memberIds.isEmpty()) return@withTransaction
            val splitAmount = amount / memberIds.size

            memberIds.forEach { memberId ->
                ensureUserExists(memberId)
                expenseSplitDao.insertSplit(
                    ExpenseSplitEntity(
                        expenseId = expenseId,
                        userId = memberId,
                        amount = splitAmount
                    )
                )
            }
        }
    }

    suspend fun getExpensesByGroup(groupId: String): List<Expense> {
        return expenseDao.getExpensesByGroup(groupId).map { entity ->
            val payers = expensePayerDao.getPayersForExpense(entity.id)
            Expense(
                id = entity.id,
                amount = entity.amount,
                description = entity.description,
                date = entity.date,
                groupId = entity.groupId,
                paidByUserId = entity.paidBy,
                imagePath = entity.imagePath,
                payerContributions = if (payers.isNotEmpty()) {
                    payers.map { payer -> PayerContribution(userId = payer.userId, amount = payer.amount) }
                } else {
                    listOf(PayerContribution(userId = entity.paidBy, amount = entity.amount))
                }
            )
        }
    }

    suspend fun upsertExpensesForGroup(
        groupId: String,
        expenses: List<Expense>,
        sharesByExpenseId: Map<String, List<ExpenseShare>>
    ) {
        database.withTransaction {
            if (groupDao.getGroupById(groupId) == null) return@withTransaction

            expenses.forEach { expense ->
                val payerContributions = expense.payerContributions
                    .ifEmpty { listOf(PayerContribution(userId = expense.paidByUserId, amount = expense.amount)) }
                payerContributions.forEach { contribution -> ensureUserExists(contribution.userId) }
                val primaryPayer = payerContributions.maxByOrNull { it.amount }?.userId ?: expense.paidByUserId
                expenseDao.insertExpense(
                    ExpenseEntity(
                        id = expense.id,
                        amount = expense.amount,
                        description = expense.description,
                        date = expense.date,
                        groupId = expense.groupId,
                        paidBy = primaryPayer,
                        imagePath = expense.imagePath
                    )
                )
                expensePayerDao.deletePayersForExpense(expense.id)
                payerContributions.forEach { contribution ->
                    expensePayerDao.insertPayer(
                        ExpensePayerEntity(
                            expenseId = expense.id,
                            userId = contribution.userId,
                            amount = contribution.amount
                        )
                    )
                }

                val incomingShares = sharesByExpenseId[expense.id].orEmpty()
                val existingShares = expenseSplitDao.getSplitsForExpense(expense.id)
                if (incomingShares.isNotEmpty()) {
                    val shouldReplaceSplits = existingShares.isEmpty() || incomingShares.size >= existingShares.size
                    if (shouldReplaceSplits) {
                        expenseSplitDao.deleteSplitsForExpense(expense.id)

                        incomingShares.forEach { share ->
                            ensureUserExists(share.userId)
                            expenseSplitDao.insertSplit(
                                ExpenseSplitEntity(
                                    expenseId = expense.id,
                                    userId = share.userId,
                                    amount = share.amount
                                )
                            )
                        }
                    }
                } else {
                    val hasExistingSplits = existingShares.isNotEmpty()
                    if (!hasExistingSplits) {
                        // If we have never seen splits for this expense, keep it explicitly empty.
                        expenseSplitDao.deleteSplitsForExpense(expense.id)
                    }
                    // Otherwise keep existing local splits to avoid transient remote empty snapshots
                    // causing debts to collapse to zero.
                }
            }
        }
    }

    suspend fun getExpenseSharesForExpense(expenseId: String): List<ExpenseShare> {
        return expenseSplitDao.getSplitsForExpense(expenseId).map { split ->
            ExpenseShare(
                userId = split.userId,
                amount = split.amount
            )
        }
    }

    suspend fun getExpenseById(expenseId: String): Expense? {
        val entity = expenseDao.getExpenseById(expenseId) ?: return null
        val payers = expensePayerDao.getPayersForExpense(expenseId)
        return Expense(
            id = entity.id,
            amount = entity.amount,
            description = entity.description,
            date = entity.date,
            groupId = entity.groupId,
            paidByUserId = entity.paidBy,
            imagePath = entity.imagePath,
            payerContributions = if (payers.isNotEmpty()) {
                payers.map { payer -> PayerContribution(userId = payer.userId, amount = payer.amount) }
            } else {
                listOf(PayerContribution(userId = entity.paidBy, amount = entity.amount))
            }
        )
    }

    suspend fun getExpensePayersForExpense(expenseId: String): List<PayerContribution> {
        return expensePayerDao.getPayersForExpense(expenseId).map { payer ->
            PayerContribution(userId = payer.userId, amount = payer.amount)
        }
    }

    suspend fun addUserToGroup(groupId: String, userId: String): Boolean {
        val insertResult = groupMemberDao.addMember(
            GroupMemberEntity(
                groupId = groupId,
                userId = userId
            )
        )
        return insertResult != -1L
    }

    suspend fun isUserInGroup(groupId: String, userId: String): Boolean {
        return groupMemberDao.isUserInGroup(groupId, userId)
    }

    private suspend fun ensureUserExists(userId: String) {
        if (userDao.getUserById(userId) != null) return
        userDao.insertUser(
            UserEntity(
                id = userId,
                name = userId,
                email = null,
                isAnonymous = false,
                isDeleted = false,
                lastSeen = System.currentTimeMillis()
            )
        )
    }
}