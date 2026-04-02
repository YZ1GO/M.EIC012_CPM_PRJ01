package com.cpm.cleave.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val currency: String,
    val joinCode: String,
    val imageUrl: String? = null
)
