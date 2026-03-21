package com.cpm.cleave.data.repository.contracts

import com.cpm.cleave.data.repository.AnonymousLimits
import com.cpm.cleave.model.User

interface IAuthRepository {
    fun getAnonymousLimits(): AnonymousLimits
    suspend fun getCurrentUser(): Result<User?>
    suspend fun getOrCreateAnonymousUser(defaultName: String = "Guest"): Result<User>

    // TODO(remove-before-release)
    suspend fun switchDebugAnonymousUser(defaultName: String = "Guest"): Result<User>
    // TODO(remove-before-release)
    suspend fun clearDebugDatabase(): Result<Unit>
}
