package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Group

class GetGroupsUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(): Result<List<Group>> {
        return groupRepository.getGroups()
    }
}
