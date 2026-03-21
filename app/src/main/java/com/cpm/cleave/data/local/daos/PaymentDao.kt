package com.cpm.cleave.data.local.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.cpm.cleave.data.local.entities.PaymentEntity

@Dao
interface PaymentDao {
    @Insert
    suspend fun insertPayment(payment: PaymentEntity)

    @Update
    suspend fun updatePayment(payment: PaymentEntity)

    @Delete
    suspend fun deletePayment(payment: PaymentEntity)

    @Query("SELECT * FROM payments WHERE userId = :userId")
    suspend fun getPaymentsForUser(userId: String): List<PaymentEntity>
}
