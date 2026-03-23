package com.cpm.cleave.data.local.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.cpm.cleave.data.local.entities.ExpenseSplitEntity

@Dao
interface ExpenseSplitDao {
    @Insert
    suspend fun insertSplit(split: ExpenseSplitEntity)

    @Delete
    suspend fun deleteSplit(split: ExpenseSplitEntity)

    @Query("SELECT * FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun getSplitsForExpense(expenseId: String): List<ExpenseSplitEntity>

    @Query("SELECT * FROM expense_splits WHERE userId = :userId")
    suspend fun getSplitsForUser(userId: String): List<ExpenseSplitEntity>

    @Query("DELETE FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun deleteSplitsForExpense(expenseId: String)

    @Query("UPDATE expense_splits SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignUser(oldUserId: String, newUserId: String)
}
