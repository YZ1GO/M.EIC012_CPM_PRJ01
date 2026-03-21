package com.cpm.cleave.data.local.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.cpm.cleave.data.local.entities.ExpenseEntity

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insertExpense(expense: ExpenseEntity)

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getExpenseById(id: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE groupId = :groupId")
    suspend fun getExpensesByGroup(groupId: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE paidBy = :userId")
    suspend fun getExpensesPaidBy(userId: String): List<ExpenseEntity>
}
