package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.domain.repository.DEFAULT_ANONYMOUS_LIMITS
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.model.User

class AuthRepositoryImpl(
    private val authSessionStore: AuthSessionStore
) : IAuthRepository {

    override fun getAnonymousLimits(): AnonymousLimits = DEFAULT_ANONYMOUS_LIMITS

    override suspend fun getCurrentUser(): Result<User?> {
        return try {
            Result.success(authSessionStore.getActiveUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrCreateAnonymousUser(defaultName: String): Result<User> {
        return try {
            Result.success(authSessionStore.getOrCreateUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO: delete
    override suspend fun switchDebugAnonymousUser(defaultName: String): Result<User> {
        return try {
            Result.success(authSessionStore.switchToNewDebugAnonymousUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO: delete
    override suspend fun clearDebugDatabase(): Result<Unit> {
        return try {
            authSessionStore.clearAllDebugData()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
