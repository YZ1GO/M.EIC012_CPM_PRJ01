package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository

class GetAddExpenseMembersUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(groupId: String): Result<List<String>> {
        return groupRepository.getGroupById(groupId)
            .map { group ->
                group?.members ?: throw IllegalArgumentException("Group not found.")
            }
    }
}
