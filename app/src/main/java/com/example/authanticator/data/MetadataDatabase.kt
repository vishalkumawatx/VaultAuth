package com.example.authanticator.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountMetadata(
    @PrimaryKey val id: String,
    val issuer: String,
    val account: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val favorite: Boolean = false,
    val folder: String = "",
    @ColumnInfo(defaultValue = "'TOTP'") val otpType: String = "TOTP",
    @ColumnInfo(defaultValue = "0") val counter: Long = 0
)

@Dao
abstract class AccountDao {
    @Query("SELECT * FROM accounts ORDER BY favorite DESC, issuer COLLATE NOCASE, account COLLATE NOCASE")
    abstract fun observe(): Flow<List<AccountMetadata>>

    @Query("DELETE FROM accounts")
    abstract fun clear()

    @Insert
    abstract fun insert(accounts: List<AccountMetadata>)

    @Transaction
    open fun replace(accounts: List<AccountMetadata>) {
        clear()
        insert(accounts)
    }
}

@Database(entities = [AccountMetadata::class], version = 2, exportSchema = true)
abstract class MetadataDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN otpType TEXT NOT NULL DEFAULT 'TOTP'")
                db.execSQL("ALTER TABLE accounts ADD COLUMN counter INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}