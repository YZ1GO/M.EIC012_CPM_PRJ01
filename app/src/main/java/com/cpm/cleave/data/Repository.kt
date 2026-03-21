package com.cpm.cleave.data

import com.cpm.cleave.model.Group
import java.util.UUID

class Repository(
    private val cache: Cache
) {
    suspend fun createGroup(name: String, currency: String): Result<Group> {
        return try {
            val newGroup = Group(
                id = UUID.randomUUID().toString(),
                name = name,
                currency = currency,
                members = emptyList(),
                joinCode = (1000..9999).random().toString(), // TODO CHANGE TO HASH
                balances = emptyMap()
            )

            cache.insertGroup(newGroup)

            Result.success(newGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroups(): Result<List<Group>> {
        return try {
            Result.success(cache.loadGroups())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroupById(groupId: String): Result<Group?> {
        return try {
            Result.success(cache.getGroupById(groupId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}