package com.cpm.cleave.data.local

import android.content.pm.ApplicationInfo
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cpm.cleave.data.local.daos.DebtDao
import com.cpm.cleave.data.local.daos.ExpenseDao
import com.cpm.cleave.data.local.daos.ExpensePayerDao
import com.cpm.cleave.data.local.daos.ExpenseSplitDao
import com.cpm.cleave.data.local.daos.GroupDao
import com.cpm.cleave.data.local.daos.GroupMemberDao
import com.cpm.cleave.data.local.daos.PaymentDao
import com.cpm.cleave.data.local.daos.UserDao
import com.cpm.cleave.data.local.entities.DebtEntity
import com.cpm.cleave.data.local.entities.ExpenseEntity
import com.cpm.cleave.data.local.entities.ExpensePayerEntity
import com.cpm.cleave.data.local.entities.ExpenseSplitEntity
import com.cpm.cleave.data.local.entities.GroupEntity
import com.cpm.cleave.data.local.entities.GroupMemberEntity
import com.cpm.cleave.data.local.entities.PaymentEntity
import com.cpm.cleave.data.local.entities.UserEntity

@Database(
    entities = [
        UserEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ExpenseEntity::class,
        ExpensePayerEntity::class,
        ExpenseSplitEntity::class,
        DebtEntity::class,
        PaymentEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class CleaveDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun expensePayerDao(): ExpensePayerDao
    abstract fun expenseSplitDao(): ExpenseSplitDao
    abstract fun debtDao(): DebtDao
    abstract fun paymentDao(): PaymentDao

    companion object {
        @Volatile
        private var INSTANCE: CleaveDatabase? = null

        fun getDatabase(context: Context): CleaveDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    CleaveDatabase::class.java,
                    "cleave_database"
                )

                val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (isDebuggable) {
                    builder.fallbackToDestructiveMigration(dropAllTables = true)
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }
    }
}
