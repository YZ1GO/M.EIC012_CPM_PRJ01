package com.cpm.cleave.data

import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User
import java.util.UUID

class Repository(
    private val cache: Cache
) {
    // Anonymous mode policy. Only maxGroups is enforced right now.
    // TODO(anonymous-limits): enforce maxExpensesPerGroup when expense creation flow exists.
    // TODO(anonymous-limits): enforce maxTotalDebt when debt calculation/settlement flow exists.
    // TODO(anonymous-limits): enforce feature locks (receipt OCR, QR join/share, cloud sync) when those flows are added.
    data class AnonymousLimits(
        val maxGroups: Int = 1,
        val maxExpensesPerGroup: Int = 10,
        val maxTotalDebt: Double = 200.0
    )

    private companion object {
        val ANONYMOUS_LIMITS = AnonymousLimits()
    }

    fun getAnonymousLimits(): AnonymousLimits = ANONYMOUS_LIMITS

    suspend fun getCurrentUser(): Result<User?> {
        // TODO(auth-refactor): after auth/session exists, resolve generic active user (anonymous or registered).
        return try {
            Result.success(cache.getActiveAnonymousUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOrCreateAnonymousUser(defaultName: String = "Guest"): Result<User> {
        return try {
            Result.success(cache.getOrCreateAnonymousUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO(remove-before-release): remove debug-only local user switch API.
    suspend fun switchDebugAnonymousUser(defaultName: String = "Guest"): Result<User> {
        return try {
            Result.success(cache.switchToNewDebugAnonymousUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO(remove-before-release): remove debug-only local database reset API.
    suspend fun clearDebugDatabase(): Result<Unit> {
        return try {
            cache.clearAllDebugData()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createGroup(name: String, currency: String): Result<Group> {
        return try {
            val anonymousUser = cache.getActiveAnonymousUser()
            if (anonymousUser != null && anonymousUser.groups.size >= ANONYMOUS_LIMITS.maxGroups) {
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

    suspend fun getGroups(): Result<List<Group>> {
        return try {
            Result.success(cache.loadGroups())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroupById(groupId: String): Result<Group?> {
        return try {
            Result.success(cache.getGroupById(groupId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinGroupByCode(joinCode: String): Result<Group> {
        return try {
            val group = cache.getGroupByJoinCode(joinCode)
                ?: return Result.failure(IllegalArgumentException("Invalid group code."))

            val currentUser = cache.getActiveAnonymousUser()
                ?: return Result.failure(IllegalStateException("No current user found."))

            if (currentUser.isAnonymous && currentUser.groups.size >= ANONYMOUS_LIMITS.maxGroups) {
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