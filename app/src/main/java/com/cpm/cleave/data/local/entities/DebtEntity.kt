package com.cpm.cleave.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "debts",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["fromUser"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["toUser"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["fromUser"]),
        Index(value = ["toUser"]),
        Index(value = ["groupId", "fromUser", "toUser"], unique = true)
    ]
)
data class DebtEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val groupId: String,
    val fromUser: String,
    val toUser: String,
    val amount: Double
)
