package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.User
import java.util.UUID
import kotlin.random.Random

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

        val joinCode = (1..JOIN_CODE_LENGTH)
            .map { JOIN_CODE_ALPHABET[Random.nextInt(JOIN_CODE_ALPHABET.length)] }
            .joinToString(separator = "")

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

    companion object {
        private const val JOIN_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val JOIN_CODE_LENGTH = 8
    }
}
