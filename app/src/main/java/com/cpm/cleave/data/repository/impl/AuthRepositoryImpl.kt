package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.repository.AnonymousLimits
import com.cpm.cleave.data.repository.DEFAULT_ANONYMOUS_LIMITS
import com.cpm.cleave.data.repository.contracts.IAuthRepository
import com.cpm.cleave.model.User

class AuthRepositoryImpl(
    private val cache: Cache
) : IAuthRepository {

    override fun getAnonymousLimits(): AnonymousLimits = DEFAULT_ANONYMOUS_LIMITS

    override suspend fun getCurrentUser(): Result<User?> {
        // TODO(auth-refactor): after auth/session exists, resolve generic active user (anonymous or registered).
        return try {
            Result.success(cache.getActiveAnonymousUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrCreateAnonymousUser(defaultName: String): Result<User> {
        return try {
            Result.success(cache.getOrCreateAnonymousUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO(remove-before-release): remove debug-only local user switch API.
    override suspend fun switchDebugAnonymousUser(defaultName: String): Result<User> {
        return try {
            Result.success(cache.switchToNewDebugAnonymousUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO(remove-before-release): remove debug-only local database reset API.
    override suspend fun clearDebugDatabase(): Result<Unit> {
        return try {
            cache.clearAllDebugData()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
