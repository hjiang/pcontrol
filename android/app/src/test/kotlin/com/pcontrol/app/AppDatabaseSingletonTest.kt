package com.pcontrol.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.BackfillStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the test-only singleton reset (issue #80). [AppDatabase.getInstance]
 * caches a process-wide instance it never clears, while
 * [androidx.room.RoomDatabase.close] permanently shuts down Room's executors —
 * a closed-but-cached instance still gets returned and every suspend DAO call
 * on it fails with JobCancellationException. Because Robolectric reuses static
 * state across test classes, any test that opens the real on-disk database
 * must end with [AppDatabase.closeForTests].
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseSingletonTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppDatabase.closeForTests()          // never inherit another class's instance
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        AppDatabase.closeForTests()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `getInstance after closeForTests returns a fresh usable database`() {
        val first = AppDatabase.getInstance(context)
        runBlocking { first.backfillStateDao().set(BackfillStateEntity(0, 1_000L, 5_000L)) }

        AppDatabase.closeForTests()

        val second = AppDatabase.getInstance(context)
        assertNotSame("closeForTests must drop the cached instance", first, second)
        runBlocking {
            // On a cached-but-closed instance this throws JobCancellationException.
            second.backfillStateDao().set(BackfillStateEntity(0, 7L, 9L))
            assertEquals(BackfillStateEntity(0, 7L, 9L), second.backfillStateDao().get())
        }
    }

    @Test
    fun `closeForTests recovers from a direct close of the cached instance`() {
        // The legacy hazard from BackfillStateMigrationTest: closing the handle
        // directly poisons the cache. This also exercises the double-close path.
        val first = AppDatabase.getInstance(context)
        first.close()

        AppDatabase.closeForTests()          // the tearDown replacement

        val second = AppDatabase.getInstance(context)
        assertNotSame(first, second)
        runBlocking {
            second.backfillStateDao().set(BackfillStateEntity(0, 7L, 9L))
            assertEquals(BackfillStateEntity(0, 7L, 9L), second.backfillStateDao().get())
        }
    }

    @Test
    fun `closeForTests is a no-op when no instance is cached`() {
        AppDatabase.closeForTests()
        AppDatabase.closeForTests()          // idempotent, must not throw

        val db = AppDatabase.getInstance(context)
        runBlocking { db.backfillStateDao().get() }   // usable
    }

    companion object {
        private const val DB_NAME = "pcontrol.db"
    }
}
