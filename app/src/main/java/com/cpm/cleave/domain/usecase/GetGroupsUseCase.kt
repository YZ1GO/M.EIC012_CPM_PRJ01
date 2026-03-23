package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Group
import kotlinx.coroutines.flow.Flow

class GetGroupsUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(): Result<List<Group>> {
        return groupRepository.getGroups()
    }

    fun observe(): Flow<List<Group>> {
        return groupRepository.observeGroups()
    }
}
