package com.cpm.cleave.data

import android.content.Context
import com.cpm.cleave.data.entities.GroupEntity
import com.cpm.cleave.data.entities.GroupMemberEntity
import com.cpm.cleave.data.entities.ExpenseEntity
import com.cpm.cleave.data.entities.ExpenseSplitEntity
import com.cpm.cleave.data.entities.UserEntity
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Cache(context: Context) {
    private val database = CleaveDatabase.getDatabase(context)
    private val groupDao = database.groupDao()
    private val groupMemberDao = database.groupMemberDao()
    private val expenseDao = database.expenseDao()
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

    suspend fun loadGroups(): List<Group> {
        // TODO(auth-refactor): after registered login/session is implemented, replace with generic getActiveUser().
        val activeUser = userDao.getActiveAnonymousUser() ?: return emptyList()
        val membershipRows = groupMemberDao.getGroupsOfUser(activeUser.id)

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
                balances = emptyMap() // TODO: Calculate from debts
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

    suspend fun insertExpenseWithSplit(
        expenseId: String,
        amount: Double,
        description: String,
        date: Long,
        groupId: String,
        paidBy: String,
        memberIds: List<String>
    ) {
        val expense = ExpenseEntity(
            id = expenseId,
            amount = amount,
            description = description,
            date = date,
            groupId = groupId,
            paidBy = paidBy
        )
        expenseDao.insertExpense(expense)

        if (memberIds.isEmpty()) return
        val splitAmount = amount / memberIds.size

        memberIds.forEach { memberId ->
            expenseSplitDao.insertSplit(
                ExpenseSplitEntity(
                    expenseId = expenseId,
                    userId = memberId,
                    amount = splitAmount
                )
            )
        }
    }

    suspend fun getExpensesByGroup(groupId: String): List<Expense> {
        return expenseDao.getExpensesByGroup(groupId).map { entity ->
            Expense(
                id = entity.id,
                amount = entity.amount,
                description = entity.description,
                date = entity.date,
                groupId = entity.groupId,
                paidByUserId = entity.paidBy,
                imagePath = entity.imagePath
            )
        }
    }

    suspend fun getActiveAnonymousUser(): User? {
        // TODO(auth-refactor): rename to getActiveUser() and resolve active user from session (anonymous or registered).
        return userDao.getActiveAnonymousUser()?.toDomain()
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

    suspend fun getOrCreateAnonymousUser(userName: String): User {
        val existing = userDao.getActiveAnonymousUser()
        if (existing != null) {
            return existing.toDomain()
        }

        val now = System.currentTimeMillis()
        val anonymousUser = UserEntity(
            id = "anon_$now",
            name = userName,
            email = null,
            isAnonymous = true,
            isDeleted = false,
            lastSeen = now
        )
        userDao.insertUser(anonymousUser)
        return anonymousUser.toDomain()
    }

    // TODO(remove-before-release): remove debug-only local user switch helper.
    suspend fun switchToNewDebugAnonymousUser(baseName: String = "Guest"): User {
        val now = System.currentTimeMillis()
        val userAId = "anon_debug_A"
        val userBId = "anon_debug_B"

        val userA = userDao.getUserById(userAId) ?: UserEntity(
            id = userAId,
            name = "$baseName A",
            email = null,
            isAnonymous = true,
            isDeleted = true,
            lastSeen = now
        ).also { userDao.insertUser(it) }

        val userB = userDao.getUserById(userBId) ?: UserEntity(
            id = userBId,
            name = "$baseName B",
            email = null,
            isAnonymous = true,
            isDeleted = true,
            lastSeen = now
        ).also { userDao.insertUser(it) }

        val active = userDao.getActiveAnonymousUser()
        val nextUserId = if (active?.id == userAId) userBId else userAId

        userDao.getAllUsers()
            .filter { it.isAnonymous && !it.isDeleted }
            .forEach {
                userDao.updateUser(it.copy(isDeleted = true, lastSeen = now))
            }

        val activateA = nextUserId == userAId
        userDao.updateUser(
            userA.copy(
                isDeleted = !activateA,
                lastSeen = now
            )
        )
        userDao.updateUser(
            userB.copy(
                isDeleted = activateA,
                lastSeen = now
            )
        )

        val switchedUser = userDao.getUserById(nextUserId)
            ?: throw IllegalStateException("Could not switch debug user.")
        return switchedUser.toDomain()
    }

    // TODO(remove-before-release): remove debug-only local database reset helper.
    suspend fun clearAllDebugData() {
        withContext(Dispatchers.IO) {
            database.clearAllTables()
        }
    }

    private suspend fun UserEntity.toDomain(): User {
        val userGroups = groupMemberDao.getGroupsOfUser(id).map { it.groupId }
        return User(
            id = id,
            name = name,
            email = email,
            isAnonymous = isAnonymous,
            isDeleted = isDeleted,
            lastSeen = lastSeen,
            groups = userGroups
        )
    }

}