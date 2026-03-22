package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User

class JoinGroupUseCase(
    private val anonymousLimits: AnonymousLimits
) {
    fun execute(group: Group?, currentUser: User?): Result<Unit> {
        if (group == null) {
            return Result.failure(IllegalArgumentException("Invalid group code."))
        }

        if (currentUser == null) {
            return Result.failure(IllegalStateException("No current user found."))
        }

        if (currentUser.isAnonymous && currentUser.groups.size >= anonymousLimits.maxGroups) {
            return Result.failure(
                IllegalStateException("Anonymous users can belong to only 1 group.")
            )
        }

        return Result.success(Unit)
    }
}
