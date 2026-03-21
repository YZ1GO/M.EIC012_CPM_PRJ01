package com.cpm.cleave.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val email: String?,
    val isAnonymous: Boolean,
    val isDeleted: Boolean,
    val lastSeen: Long
)
