package com.cpm.cleave.data.local

import android.content.pm.ApplicationInfo
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 8,
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

                builder.addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN receiptItemsJson TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN isSessionActive INTEGER NOT NULL DEFAULT 0")
                // Preserve previous semantics where non-deleted users were treated as active.
                db.execSQL("UPDATE users SET isSessionActive = CASE WHEN isDeleted = 0 THEN 1 ELSE 0 END")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasImageUrlColumn = db.query("PRAGMA table_info(groups)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var found = false

                    while (nameIndex >= 0 && cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "imageUrl") {
                            found = true
                            break
                        }
                    }

                    found
                }

                if (!hasImageUrlColumn) {
                    db.execSQL("ALTER TABLE groups ADD COLUMN imageUrl TEXT")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasPhotoUrlColumn = db.query("PRAGMA table_info(users)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var found = false

                    while (nameIndex >= 0 && cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "photoUrl") {
                            found = true
                            break
                        }
                    }

                    found
                }

                if (!hasPhotoUrlColumn) {
                    db.execSQL("ALTER TABLE users ADD COLUMN photoUrl TEXT")
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hasOwnerIdColumn = db.query("PRAGMA table_info(groups)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    var found = false

                    while (nameIndex >= 0 && cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "ownerId") {
                            found = true
                            break
                        }
                    }

                    found
                }

                if (!hasOwnerIdColumn) {
                    db.execSQL("ALTER TABLE groups ADD COLUMN ownerId TEXT")
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS debts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId TEXT NOT NULL,
                        fromUser TEXT NOT NULL,
                        toUser TEXT NOT NULL,
                        amount REAL NOT NULL,
                        FOREIGN KEY(fromUser) REFERENCES users(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(toUser) REFERENCES users(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO debts_new (id, groupId, fromUser, toUser, amount)
                    SELECT id, '', fromUser, toUser, amount FROM debts
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE debts")
                db.execSQL("ALTER TABLE debts_new RENAME TO debts")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_groupId ON debts(groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_fromUser ON debts(fromUser)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debts_toUser ON debts(toUser)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_debts_groupId_fromUser_toUser ON debts(groupId, fromUser, toUser)"
                )
            }
        }
    }
}
