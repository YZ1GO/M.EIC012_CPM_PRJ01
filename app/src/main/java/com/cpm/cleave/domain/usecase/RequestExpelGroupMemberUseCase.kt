package com.cpm.cleave.domain.usecase

import com.cpm.cleave.domain.repository.contracts.IGroupRepository

class RequestExpelGroupMemberUseCase(
    private val groupRepository: IGroupRepository
) {
    suspend fun execute(groupId: String, memberId: String): Result<Unit> {
        return groupRepository.expelMember(groupId = groupId, memberId = memberId)
    }
}
