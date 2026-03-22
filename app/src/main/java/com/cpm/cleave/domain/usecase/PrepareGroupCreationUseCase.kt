package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User
import java.util.UUID

data class PrepareGroupCreationCommand(
    val name: String,
    val currency: String
)

class PrepareGroupCreationUseCase {
    fun execute(
        command: PrepareGroupCreationCommand,
        currentUser: User?,
        anonymousLimits: AnonymousLimits
    ): Result<Group> {
        if (command.name.isBlank()) {
            return Result.failure(IllegalArgumentException("Group name is required."))
        }

        if (currentUser != null && currentUser.groups.size >= anonymousLimits.maxGroups) {
            return Result.failure(IllegalStateException("Anonymous users can belong to only 1 group."))
        }

        val joinCode = UUID.randomUUID().toString().replace("-", "").take(8)

        return Result.success(
            Group(
                id = UUID.randomUUID().toString(),
                name = command.name,
                currency = command.currency,
                members = currentUser?.let { listOf(it.id) } ?: emptyList(),
                joinCode = joinCode,
                balances = emptyMap()
            )
        )
    }
}
