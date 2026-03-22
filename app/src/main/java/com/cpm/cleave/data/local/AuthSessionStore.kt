package com.cpm.cleave.data.local

import android.content.Context
import com.cpm.cleave.data.local.entities.UserEntity
import com.cpm.cleave.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthSessionStore(context: Context) {
    private val database = CleaveDatabase.getDatabase(context)
    private val userDao = database.userDao()
    private val groupMemberDao = database.groupMemberDao()

    suspend fun getActiveUser(): User? {
        return userDao.getActiveUser()?.toDomain()
    }

    suspend fun getOrCreateUser(userName: String): User {
        val existing = userDao.getActiveUser()
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

    // TODO: delete
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

        val active = userDao.getActiveUser()
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

    // TODO: delete
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
