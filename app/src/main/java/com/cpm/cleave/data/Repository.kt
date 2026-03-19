package com.cpm.cleave.data

import com.cpm.cleave.model.Group

class Repository(
    private val cache: Cache
) {
    fun createGroup(name: String, currency: String): Result<Group> {
        return try {
            val currentGroups = cache.loadGroups().toMutableList()

            val newGroup = Group(
                id = currentGroups.size.toString(),
                name = name,
                currency = currency,
                members = emptyList(),
                joinCode = (1000..9999).random().toString(), // TODO CHANGE TO HASH
                balances = emptyMap()
            )

            currentGroups.add(newGroup)
            cache.saveGroups(currentGroups)

            Result.success(newGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getGroups(): Result<List<Group>> {
        return try {
            Result.success(cache.loadGroups())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}