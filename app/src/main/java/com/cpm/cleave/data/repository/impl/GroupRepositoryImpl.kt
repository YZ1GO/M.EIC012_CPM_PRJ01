package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.domain.repository.DEFAULT_ANONYMOUS_LIMITS
import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.domain.usecase.JoinGroupUseCase
import com.cpm.cleave.domain.usecase.PrepareGroupCreationCommand
import com.cpm.cleave.domain.usecase.PrepareGroupCreationUseCase
import com.cpm.cleave.model.Group

class GroupRepositoryImpl(
    private val cache: Cache,
    private val authSessionStore: AuthSessionStore,
    private val anonymousLimits: AnonymousLimits = DEFAULT_ANONYMOUS_LIMITS,
    private val prepareGroupCreationUseCase: PrepareGroupCreationUseCase = PrepareGroupCreationUseCase(),
    private val joinGroupUseCase: JoinGroupUseCase = JoinGroupUseCase(anonymousLimits)
) : IGroupRepository {

    override suspend fun createGroup(name: String, currency: String): Result<Group> {
        return try {
            val anonymousUser = authSessionStore.getActiveUser()
            val newGroup = prepareGroupCreationUseCase.execute(
                command = PrepareGroupCreationCommand(name = name, currency = currency),
                currentUser = anonymousUser,
                anonymousLimits = anonymousLimits
            ).getOrElse { return Result.failure(it) }

            cache.insertGroup(newGroup)
            anonymousUser?.let {
                cache.addUserToGroup(newGroup.id, it.id)
            }

            Result.success(newGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getGroups(): Result<List<Group>> {
        return try {
            val currentUser = authSessionStore.getActiveUser()
            if (currentUser == null) {
                return Result.success(emptyList())
            }

            Result.success(cache.loadGroups(currentUser.id))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getGroupById(groupId: String): Result<Group?> {
        return try {
            Result.success(cache.getGroupById(groupId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun joinGroupByCode(joinCode: String): Result<Group> {
        return try {
            val group = cache.getGroupByJoinCode(joinCode)
            val currentUser = authSessionStore.getActiveUser()

            joinGroupUseCase.execute(group = group, currentUser = currentUser)
                .getOrElse { return Result.failure(it) }

            val resolvedGroup = group ?: return Result.failure(IllegalArgumentException("Invalid group code."))
            val resolvedUser = currentUser ?: return Result.failure(IllegalStateException("No current user found."))

            if (!cache.isUserInGroup(resolvedGroup.id, resolvedUser.id)) {
                cache.addUserToGroup(resolvedGroup.id, resolvedUser.id)
            }

            val updatedGroup = cache.getGroupById(resolvedGroup.id) ?: resolvedGroup
            Result.success(updatedGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
