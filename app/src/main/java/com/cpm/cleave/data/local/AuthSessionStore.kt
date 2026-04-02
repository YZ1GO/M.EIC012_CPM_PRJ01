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
                isSessionActive = false,
                lastSeen = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearAllActiveSessionUsers() {
        val now = System.currentTimeMillis()
        userDao.getAllUsers()
            .filter { it.isSessionActive }
            .forEach { activeUser ->
                userDao.updateUser(
                    activeUser.copy(
                        isSessionActive = false,
                        lastSeen = now
                    )
                )
            }
    }

    suspend fun getOrCreateUser(userName: String): User {
        return database.withTransaction {
            val now = System.currentTimeMillis()
            val activeUsers = userDao.getAllUsers().filter { !it.isDeleted && it.isSessionActive }
            val activeAnonymous = activeUsers.firstOrNull { it.isAnonymous }

            if (activeAnonymous != null) {
                activeUsers
                    .filter { it.id != activeAnonymous.id }
                    .forEach { staleActive ->
                        userDao.updateUser(
                            staleActive.copy(
                                isSessionActive = false,
                                lastSeen = now
                            )
                        )
                    }

                val refreshedAnonymous = activeAnonymous.copy(
                    name = activeAnonymous.name.ifBlank { userName },
                    isSessionActive = true,
                    lastSeen = now
                )
                userDao.updateUser(refreshedAnonymous)
                return@withTransaction refreshedAnonymous.toDomain()
            }

            activeUsers.forEach { staleActive ->
                userDao.updateUser(
                    staleActive.copy(
                        isSessionActive = false,
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
                isSessionActive = true,
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
                        isSessionActive = true,
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
                        isSessionActive = true,
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
                        isSessionActive = false,
                        lastSeen = now
                    )
                )
            }

            userDao.getAllUsers()
                .filter { it.isSessionActive && it.id != registeredUserId }
                .forEach { staleActive ->
                    userDao.updateUser(
                        staleActive.copy(
                            isSessionActive = false,
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
                        isSessionActive = true,
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
                        isSessionActive = true,
                        lastSeen = now
                    )
                )
            }

            userDao.getAllUsers()
                .filter { it.isSessionActive && it.id != anonymousUserId }
                .forEach { staleActive ->
                    userDao.updateUser(
                        staleActive.copy(
                            isSessionActive = false,
                            lastSeen = now
                        )
                    )
                }

            val activeAnonymous = userDao.getUserById(anonymousUserId)
                ?: throw IllegalStateException("Could not activate anonymous user locally.")
            activeAnonymous.toDomain()
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
