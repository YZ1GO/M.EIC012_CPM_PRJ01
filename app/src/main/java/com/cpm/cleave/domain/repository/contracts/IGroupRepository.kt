package com.cpm.cleave.domain.repository.contracts

import com.cpm.cleave.model.Group

interface IGroupRepository {
    suspend fun createGroup(name: String, currency: String): Result<Group>
    suspend fun getGroups(): Result<List<Group>>
    suspend fun getGroupById(groupId: String): Result<Group?>
    suspend fun joinGroupByCode(joinCode: String): Result<Group>
}
