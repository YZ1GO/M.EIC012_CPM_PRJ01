package com.cpm.cleave.data.local.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cpm.cleave.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Delete
    suspend fun deleteUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserById(id: String): UserEntity?

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<UserEntity>

    @Query("SELECT * FROM users WHERE isDeleted = 0 AND isSessionActive = 1 ORDER BY lastSeen DESC LIMIT 1")
    suspend fun getActiveUser(): UserEntity?

    @Query("SELECT * FROM users WHERE isAnonymous = 1 AND isDeleted = 0 AND isSessionActive = 1 LIMIT 1")
    suspend fun getActiveAnonymousUser(): UserEntity?

    @Query("SELECT * FROM users WHERE isDeleted = 0 AND isSessionActive = 1 AND lastSeen = (SELECT MAX(lastSeen) FROM users WHERE isDeleted = 0 AND isSessionActive = 1) LIMIT 1")
    fun observeActiveUser(): Flow<UserEntity?>
}
