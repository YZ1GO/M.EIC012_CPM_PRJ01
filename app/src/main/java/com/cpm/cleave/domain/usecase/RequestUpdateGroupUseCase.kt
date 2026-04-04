package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Group

class RequestUpdateGroupUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(group: Group): Result<Group> {
        return groupRepository.updateGroup(group)
    }

    suspend fun uploadGroupImage(imageBytes: ByteArray): Result<String> {
        return groupRepository.uploadGroupImage(imageBytes)
    }
}