package com.cpm.cleave.domain.repository.contracts

import com.cpm.cleave.model.Group
import kotlinx.coroutines.flow.Flow

interface IGroupRepository {
    suspend fun createGroup(name: String, currency: String, imageUrl: String?): Result<Group>
    suspend fun uploadGroupImage(imageBytes: ByteArray): Result<String>
    suspend fun deleteGroup(groupId: String): Result<Unit>
    suspend fun getGroups(): Result<List<Group>>
    fun observeGroups(): Flow<List<Group>>
    suspend fun getGroupById(groupId: String): Result<Group?>
    suspend fun joinGroupByCode(joinCode: String): Result<Group>
}
