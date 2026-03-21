package com.cpm.cleave.data

import android.content.Context
import com.cpm.cleave.data.entities.GroupEntity
import com.cpm.cleave.data.entities.GroupMemberEntity
import com.cpm.cleave.data.entities.UserEntity
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User

class Cache(context: Context) {
    private val database = CleaveDatabase.getDatabase(context)
    private val groupDao = database.groupDao()
    private val groupMemberDao = database.groupMemberDao()
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
        val groupEntities = groupDao.getAllGroups()
        return groupEntities.map { entity ->
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

    suspend fun getActiveAnonymousUser(): User? {
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