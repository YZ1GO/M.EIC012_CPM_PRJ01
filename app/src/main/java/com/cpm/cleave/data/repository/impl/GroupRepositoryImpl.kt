package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.repository.AnonymousLimits
import com.cpm.cleave.data.repository.DEFAULT_ANONYMOUS_LIMITS
import com.cpm.cleave.data.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Group
import java.util.UUID

class GroupRepositoryImpl(
    private val cache: Cache,
    private val anonymousLimits: AnonymousLimits = DEFAULT_ANONYMOUS_LIMITS
) : IGroupRepository {

    override suspend fun createGroup(name: String, currency: String): Result<Group> {
        return try {
            val anonymousUser = cache.getActiveAnonymousUser()
            if (anonymousUser != null && anonymousUser.groups.size >= anonymousLimits.maxGroups) {
                return Result.failure(
                    IllegalStateException("Anonymous users can belong to only 1 group.")
                )
            }

            val newGroup = Group(
                id = UUID.randomUUID().toString(),
                name = name,
                currency = currency,
                members = anonymousUser?.let { listOf(it.id) } ?: emptyList(),
                joinCode = (1000..9999).random().toString(), // TODO CHANGE TO HASH
                balances = emptyMap()
            )

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
            Result.success(cache.loadGroups())
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
                ?: return Result.failure(IllegalArgumentException("Invalid group code."))

            val currentUser = cache.getActiveAnonymousUser()
                ?: return Result.failure(IllegalStateException("No current user found."))

            if (currentUser.isAnonymous && currentUser.groups.size >= anonymousLimits.maxGroups) {
                return Result.failure(
                    IllegalStateException("Anonymous users can belong to only 1 group.")
                )
            }

            if (!cache.isUserInGroup(group.id, currentUser.id)) {
                cache.addUserToGroup(group.id, currentUser.id)
            }

            val updatedGroup = cache.getGroupById(group.id) ?: group
            Result.success(updatedGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
