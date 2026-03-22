package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Group

class RequestJoinGroupUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(joinCode: String): Result<Group> {
        return groupRepository.joinGroupByCode(joinCode)
    }
}
