package com.cpm.cleave.data.local

import android.content.Context
import androidx.room.withTransaction
import com.cpm.cleave.data.local.entities.UserEntity
import com.cpm.cleave.data.local.entities.GroupMemberEntity
import com.cpm.cleave.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AuthSessionStore(context: Context) {
    private val database = CleaveDatabase.getDatabase(context)
    private val userDao = database.userDao()
    private val groupMemberDao = database.groupMemberDao()
    private val expenseDao = database.expenseDao()
    private val expensePayerDao = database.expensePayerDao()
    private val expenseSplitDao = database.expenseSplitDao()
    private val debtDao = database.debtDao()
    private val paymentDao = database.paymentDao()

    suspend fun getActiveUser(): User? {
        return userDao.getActiveUser()?.toDomain()
    }

    suspend fun getUserById(userId: String): User? {
        return userDao.getUserById(userId)?.toDomain()
    }

    suspend fun clearActiveSessionUser() {
        val active = userDao.getActiveUser() ?: return
        userDao.updateUser(
            active.copy(
                isDeleted = true,
                lastSeen = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearAllActiveSessionUsers() {
        val now = System.currentTimeMillis()
        userDao.getAllUsers()
            .filter { !it.isDeleted }
            .forEach { activeUser ->
                userDao.updateUser(
                    activeUser.copy(
                        isDeleted = true,
                        lastSeen = now
                    )
                )
            }
    }

    suspend fun getOrCreateUser(userName: String): User {
        return database.withTransaction {
            val now = System.currentTimeMillis()
            val activeUsers = userDao.getAllUsers().filter { !it.isDeleted }
            val activeAnonymous = activeUsers.firstOrNull { it.isAnonymous }

            if (activeAnonymous != null) {
                activeUsers
                    .filter { it.id != activeAnonymous.id }
                    .forEach { staleActive ->
                        userDao.updateUser(
                            staleActive.copy(
                                isDeleted = true,
                                lastSeen = now
                            )
                        )
                    }

                val refreshedAnonymous = activeAnonymous.copy(
                    name = activeAnonymous.name.ifBlank { userName },
                    isDeleted = false,
                    lastSeen = now
                )
                userDao.updateUser(refreshedAnonymous)
                return@withTransaction refreshedAnonymous.toDomain()
            }

            activeUsers.forEach { staleActive ->
                userDao.updateUser(
                    staleActive.copy(
                        isDeleted = true,
                        lastSeen = now
                    )
                )
            }

            val anonymousUser = UserEntity(
                id = "anon_$now",
                name = userName,
                email = null,
                isAnonymous = true,
                isDeleted = false,
                lastSeen = now
            )
            userDao.insertUser(anonymousUser)
            anonymousUser.toDomain()
        }
    }

    suspend fun activateRegisteredUserAfterAuthentication(
        registeredUserId: String,
        registeredName: String,
        registeredEmail: String?,
        mergeAnonymousData: Boolean
    ): User {
        return database.withTransaction {
            val now = System.currentTimeMillis()
            val activeAnonymous = userDao.getActiveAnonymousUser()

            val existingRegistered = userDao.getUserById(registeredUserId)
            if (existingRegistered == null) {
                userDao.insertUser(
                    UserEntity(
                        id = registeredUserId,
                        name = registeredName,
                        email = registeredEmail,
                        isAnonymous = false,
                        isDeleted = false,
                        lastSeen = now
                    )
                )
            } else {
                userDao.updateUser(
                    existingRegistered.copy(
                        name = registeredName,
                        email = registeredEmail,
                        isAnonymous = false,
                        isDeleted = false,
                        lastSeen = now
                    )
                )
            }

            if (activeAnonymous != null && activeAnonymous.id != registeredUserId) {
                val oldUserId = activeAnonymous.id

                if (mergeAnonymousData) {
                    // Reassign group memberships with duplicate-safe upsert semantics.
                    val oldMemberships = groupMemberDao.getGroupsOfUser(oldUserId)
                    oldMemberships.forEach { membership ->
                        if (!groupMemberDao.isUserInGroup(membership.groupId, registeredUserId)) {
                            groupMemberDao.addMember(
                                GroupMemberEntity(
                                    groupId = membership.groupId,
                                    userId = registeredUserId
                                )
                            )
                        }
                        groupMemberDao.removeMember(membership)
                    }

                    expenseDao.reassignPaidByUser(oldUserId, registeredUserId)
                    expensePayerDao.reassignUser(oldUserId, registeredUserId)
                    expenseSplitDao.reassignUser(oldUserId, registeredUserId)
                    debtDao.reassignFromUser(oldUserId, registeredUserId)
                    debtDao.reassignToUser(oldUserId, registeredUserId)
                    paymentDao.reassignUser(oldUserId, registeredUserId)
                }

                userDao.updateUser(
                    activeAnonymous.copy(
                        isDeleted = true,
                        lastSeen = now
                    )
                )
            }

            userDao.getAllUsers()
                .filter { !it.isDeleted && it.id != registeredUserId }
                .forEach { staleActive ->
                    userDao.updateUser(
                        staleActive.copy(
                            isDeleted = true,
                            lastSeen = now
                        )
                    )
                }

            val mergedUser = userDao.getUserById(registeredUserId)
                ?: throw IllegalStateException("Could not activate registered user locally.")
            mergedUser.toDomain()
        }
    }

    suspend fun activateAnonymousUserSession(
        anonymousUserId: String,
        anonymousName: String = "Guest"
    ): User {
        return database.withTransaction {
            val now = System.currentTimeMillis()

            val existingAnonymous = userDao.getUserById(anonymousUserId)
            if (existingAnonymous == null) {
                userDao.insertUser(
                    UserEntity(
                        id = anonymousUserId,
                        name = anonymousName,
                        email = null,
                        isAnonymous = true,
                        isDeleted = false,
                        lastSeen = now
                    )
                )
            } else {
                userDao.updateUser(
                    existingAnonymous.copy(
                        name = existingAnonymous.name.ifBlank { anonymousName },
                        email = null,
                        isAnonymous = true,
                        isDeleted = false,
                        lastSeen = now
                    )
                )
            }

            userDao.getAllUsers()
                .filter { !it.isDeleted && it.id != anonymousUserId }
                .forEach { staleActive ->
                    userDao.updateUser(
                        staleActive.copy(
                            isDeleted = true,
                            lastSeen = now
                        )
                    )
                }

            val activeAnonymous = userDao.getUserById(anonymousUserId)
                ?: throw IllegalStateException("Could not activate anonymous user locally.")
            activeAnonymous.toDomain()
        }
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

    fun observeActiveUser(): Flow<User?> {
        return userDao.observeActiveUser().map { it?.toDomain() }
    }
}
