package com.cpm.cleave.data.local.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cpm.cleave.data.local.entities.ExpensePayerEntity

@Dao
interface ExpensePayerDao {
    @Insert
    suspend fun insertPayer(payer: ExpensePayerEntity)

    @Query("SELECT * FROM expense_payers WHERE expenseId = :expenseId")
    suspend fun getPayersForExpense(expenseId: String): List<ExpensePayerEntity>

    @Query("DELETE FROM expense_payers WHERE expenseId = :expenseId")
    suspend fun deletePayersForExpense(expenseId: String)

    @Query("UPDATE expense_payers SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignUser(oldUserId: String, newUserId: String)
}
