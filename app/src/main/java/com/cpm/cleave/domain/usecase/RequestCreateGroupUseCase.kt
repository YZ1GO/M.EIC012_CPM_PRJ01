package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Group

class RequestCreateGroupUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(name: String, currency: String): Result<Group> {
        return groupRepository.createGroup(name, currency)
    }
}
