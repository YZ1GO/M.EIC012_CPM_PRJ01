package com.cpm.cleave.data.local.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.cpm.cleave.data.local.entities.DebtEntity

@Dao
interface DebtDao {
    @Insert
    suspend fun insertDebt(debt: DebtEntity)

    @Update
    suspend fun updateDebt(debt: DebtEntity)

    @Delete
    suspend fun deleteDebt(debt: DebtEntity)

    @Query("SELECT * FROM debts WHERE fromUser = :userId OR toUser = :userId")
    suspend fun getDebtsForUser(userId: String): List<DebtEntity>

    @Query("SELECT * FROM debts WHERE fromUser = :fromUser AND toUser = :toUser")
    suspend fun getDebtBetween(fromUser: String, toUser: String): DebtEntity?

    @Query("UPDATE debts SET fromUser = :newUserId WHERE fromUser = :oldUserId")
    suspend fun reassignFromUser(oldUserId: String, newUserId: String)

    @Query("UPDATE debts SET toUser = :newUserId WHERE toUser = :oldUserId")
    suspend fun reassignToUser(oldUserId: String, newUserId: String)
}
