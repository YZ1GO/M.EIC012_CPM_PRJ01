package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository

class RequestDeleteGroupUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(groupId: String): Result<Unit> {
        return groupRepository.deleteGroup(groupId)
    }
}
