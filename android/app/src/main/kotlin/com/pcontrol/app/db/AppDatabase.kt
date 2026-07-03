package com.pcontrol.app.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

// --- DAOs ---

@Dao
interface UsageCounterDao {
    /** Increment [seconds] for the given (day, kind, subject). If the row exists, add to `seconds`. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(counter: UsageCounterEntity)

    /** Get a specific counter row, or null. */
    @Query("SELECT * FROM usage_counter WHERE day = :day AND kind = :kind AND subject = :subject")
    suspend fun get(day: String, kind: String, subject: String): UsageCounterEntity?

    /** Get all counters for a given day. */
    @Query("SELECT * FROM usage_counter WHERE day = :day")
    suspend fun getDay(day: String): List<UsageCounterEntity>

    /** Get all counters where seconds > syncedSeconds (unsynced deltas). */
    @Query("SELECT * FROM usage_counter WHERE seconds > syncedSeconds")
    suspend fun getUnsynced(): List<UsageCounterEntity>

    /** Mark counters as synced after successful upload. */
    @Query("UPDATE usage_counter SET syncedSeconds = seconds WHERE day = :day AND kind = :kind AND subject = :subject")
    suspend fun markSynced(day: String, kind: String, subject: String)
}

@Dao
interface CachedPolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: CachedPolicyEntity)

    @Query("SELECT * FROM cached_policy WHERE id = 1")
    suspend fun get(): CachedPolicyEntity?
}

// --- Database ---

@Database(
    entities = [UsageCounterEntity::class, CachedPolicyEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageCounterDao(): UsageCounterDao
    abstract fun cachedPolicyDao(): CachedPolicyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pcontrol.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
