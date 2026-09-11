package com.pcontrol.app.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
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

    /** Mark counters as synced up to [sentSeconds] after successful upload.
     * Uses the snapshot seconds value recorded before the network call to
     * avoid losing ticks recorded during the HTTP request. */
    @Query("UPDATE usage_counter SET syncedSeconds = :sentSeconds WHERE day = :day AND kind = :kind AND subject = :subject")
    suspend fun markSynced(day: String, kind: String, subject: String, sentSeconds: Int)
}

@Dao
interface WarnedSubjectDao {
    /** Check if a (day, subject) was already warned. */
    @Query("SELECT COUNT(*) FROM warned_subject WHERE day = :day AND subject = :subject")
    suspend fun exists(day: String, subject: String): Int

    /** Record a warned subject for deduplication. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(warned: WarnedSubjectEntity)

    /** Remove warned entries for days other than [today] (day rollover cleanup). */
    @Query("DELETE FROM warned_subject WHERE day != :today")
    suspend fun deleteOtherDays(today: String)
}

@Entity(tableName = "warned_subject", primaryKeys = ["day", "subject"])
data class WarnedSubjectEntity(
    val day: String,      // "YYYY-MM-DD"
    val subject: String   // package name or domain
)

/**
 * Durable pending usage-backfill recovery window (singleton row, id = 0).
 *
 * `endMs > 0` means usage in `(progressMs, endMs]` has not yet been
 * durably reconstructed from system UsageStats and must be replayed — on
 * service start, on a ≥ 2 min loop stall, or after a crash, whichever
 * comes first. Critically, this state lives in Room (not SharedPreferences)
 * so the live tick path cannot overwrite it: a failed backfill query/merge
 * leaves the outage frontier intact for retry, and per-chunk progress is
 * committed in the same transaction as the chunk's counter merges, so a
 * crash can neither lose merged slices nor double-count them.
 */
@Entity(tableName = "backfill_state")
data class BackfillStateEntity(
    @PrimaryKey val id: Int, // always 0 — singleton row
    val progressMs: Long,    // wall-clock frontier recovered through so far
    val endMs: Long          // 0 = no recovery pending
)

@Dao
interface BackfillStateDao {
    @Query("SELECT * FROM backfill_state WHERE id = 0")
    suspend fun get(): BackfillStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(state: BackfillStateEntity)

    /** Never moves the frontier backwards. */
    @Query("UPDATE backfill_state SET progressMs = MAX(progressMs, :progressMs) WHERE id = 0")
    suspend fun advanceProgress(progressMs: Long)

    @Query("UPDATE backfill_state SET progressMs = 0, endMs = 0 WHERE id = 0")
    suspend fun clear()
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
    entities = [UsageCounterEntity::class, CachedPolicyEntity::class, WarnedSubjectEntity::class, BackfillStateEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageCounterDao(): UsageCounterDao
    abstract fun warnedSubjectDao(): WarnedSubjectDao
    abstract fun cachedPolicyDao(): CachedPolicyDao
    abstract fun backfillStateDao(): BackfillStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) { database ->
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS warned_subject (
                    day TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    PRIMARY KEY(day, subject)
                )
            """.trimIndent())
        }

        private val MIGRATION_2_3 = androidx.room.migration.Migration(2, 3) { database ->
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS backfill_state (
                    id INTEGER NOT NULL PRIMARY KEY,
                    progressMs INTEGER NOT NULL,
                    endMs INTEGER NOT NULL
                )
            """.trimIndent())
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pcontrol.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
