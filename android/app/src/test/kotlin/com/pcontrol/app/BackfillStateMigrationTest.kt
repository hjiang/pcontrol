package com.pcontrol.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.BackfillStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the v2 → v3 migration on a real on-disk database: existing
 * installs upgrade through [AppDatabase.MIGRATION_2_3] (hand-written SQL),
 * which the in-memory DAO tests never execute. Also asserts the
 * [com.pcontrol.app.db.BackfillStateDao] contract the recovery logic relies
 * on: monotonic [advanceProgress][com.pcontrol.app.db.BackfillStateDao.advanceProgress]
 * and a zeroing [clear][com.pcontrol.app.db.BackfillStateDao.clear].
 */
@RunWith(RobolectricTestRunner::class)
class BackfillStateMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    /** Hand-builds the version-2 schema (pre-`backfill_state`) on disk. The
     *  DDL must match the v2 entities so Room's post-migration validation
     *  passes for every table. */
    private fun createV2Database() {
        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        db.use {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS usage_counter (
                    day TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    label TEXT NOT NULL,
                    seconds INTEGER NOT NULL,
                    syncedSeconds INTEGER NOT NULL,
                    PRIMARY KEY(day, kind, subject)
                )
                """.trimIndent()
            )
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cached_policy (
                    id INTEGER NOT NULL PRIMARY KEY,
                    version INTEGER NOT NULL,
                    json TEXT NOT NULL
                )
                """.trimIndent()
            )
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS warned_subject (
                    day TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    PRIMARY KEY(day, subject)
                )
                """.trimIndent()
            )
            it.version = 2
        }
    }

    @Test
    fun `migration 2 to 3 creates backfill_state and dao contract holds`() {
        createV2Database()

        // Opening through the app's configuration must run MIGRATION_2_3.
        val db = AppDatabase.getInstance(context)
        assertEquals(3, db.openHelper.writableDatabase.version)

        runBlocking {
            val dao = db.backfillStateDao()

            // Fresh migration: no pending recovery row exists yet.
            assertNull(dao.get())

            // set + get round-trip.
            dao.set(BackfillStateEntity(0, 1_000L, 5_000L))
            assertEquals(BackfillStateEntity(0, 1_000L, 5_000L), dao.get())

            // advanceProgress is monotonic: never moves the frontier back.
            dao.advanceProgress(3_000L)
            assertEquals(3_000L, dao.get()!!.progressMs)
            dao.advanceProgress(2_000L)
            assertEquals(3_000L, dao.get()!!.progressMs)

            // clear zeroes the singleton row.
            dao.clear()
            val cleared = dao.get()!!
            assertEquals(0L, cleared.progressMs)
            assertEquals(0L, cleared.endMs)
        }
        db.close()
    }

    companion object {
        // The app's real database name — getInstance() configures Room with
        // it (including the private migrations), which is what the test opens.
        private const val DB_NAME = "pcontrol.db"
    }
}
