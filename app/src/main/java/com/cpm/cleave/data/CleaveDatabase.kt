package com.cpm.cleave.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cpm.cleave.data.daos.DebtDao
import com.cpm.cleave.data.daos.ExpenseDao
import com.cpm.cleave.data.daos.ExpenseSplitDao
import com.cpm.cleave.data.daos.GroupDao
import com.cpm.cleave.data.daos.GroupMemberDao
import com.cpm.cleave.data.daos.PaymentDao
import com.cpm.cleave.data.daos.UserDao
import com.cpm.cleave.data.entities.DebtEntity
import com.cpm.cleave.data.entities.ExpenseEntity
import com.cpm.cleave.data.entities.ExpenseSplitEntity
import com.cpm.cleave.data.entities.GroupEntity
import com.cpm.cleave.data.entities.GroupMemberEntity
import com.cpm.cleave.data.entities.PaymentEntity
import com.cpm.cleave.data.entities.UserEntity

@Database(
    entities = [
        UserEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ExpenseEntity::class,
        ExpenseSplitEntity::class,
        DebtEntity::class,
        PaymentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CleaveDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun expenseSplitDao(): ExpenseSplitDao
    abstract fun debtDao(): DebtDao
    abstract fun paymentDao(): PaymentDao

    companion object {
        @Volatile
        private var INSTANCE: CleaveDatabase? = null

        fun getDatabase(context: Context): CleaveDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CleaveDatabase::class.java,
                    "cleave_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
