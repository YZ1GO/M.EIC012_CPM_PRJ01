package com.cpm.cleave.domain.repository.contracts

import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.model.User

interface IAuthRepository {
    fun getAnonymousLimits(): AnonymousLimits
    suspend fun getCurrentUser(): Result<User?>
    suspend fun getOrCreateAnonymousUser(defaultName: String = "Guest"): Result<User>

    // TODO: delete
    suspend fun switchDebugAnonymousUser(defaultName: String = "Guest"): Result<User>
    // TODO: delete
    suspend fun clearDebugDatabase(): Result<Unit>
}
