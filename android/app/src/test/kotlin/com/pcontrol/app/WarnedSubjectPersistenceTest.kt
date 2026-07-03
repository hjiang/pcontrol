package com.pcontrol.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.WarnedSubjectEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WarnedSubjectPersistenceTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `exists returns 0 for new subject`() {
        runBlocking {
            val count = db.warnedSubjectDao().exists("2026-07-03", "com.example.Game")
            assertEquals(0, count)
        }
    }

    @Test
    fun `insert then exists returns 1`() {
        runBlocking {
            db.warnedSubjectDao().insert(WarnedSubjectEntity("2026-07-03", "com.example.Game"))
            val count = db.warnedSubjectDao().exists("2026-07-03", "com.example.Game")
            assertEquals(1, count)
        }
    }

    @Test
    fun `exists returns 1 same day different subject returns 0`() {
        runBlocking {
            db.warnedSubjectDao().insert(WarnedSubjectEntity("2026-07-03", "com.example.Game"))
            assertEquals(1, db.warnedSubjectDao().exists("2026-07-03", "com.example.Game"))
            assertEquals(0, db.warnedSubjectDao().exists("2026-07-03", "com.example.YouTube"))
        }
    }

    @Test
    fun `exists returns 0 for different day`() {
        runBlocking {
            db.warnedSubjectDao().insert(WarnedSubjectEntity("2026-07-03", "com.example.Game"))
            assertEquals(0, db.warnedSubjectDao().exists("2026-07-04", "com.example.Game"))
        }
    }

    @Test
    fun `deleteOtherDays removes old days`() {
        runBlocking {
            db.warnedSubjectDao().insert(WarnedSubjectEntity("2026-07-01", "com.example.Game"))
            db.warnedSubjectDao().insert(WarnedSubjectEntity("2026-07-03", "com.example.Game"))

            db.warnedSubjectDao().deleteOtherDays("2026-07-03")

            assertEquals(1, db.warnedSubjectDao().exists("2026-07-03", "com.example.Game"))
            assertEquals(0, db.warnedSubjectDao().exists("2026-07-01", "com.example.Game"))
        }
    }

    @Test
    fun `duplicate insert keeps count at 1`() {
        runBlocking {
            db.warnedSubjectDao().insert(WarnedSubjectEntity("2026-07-03", "com.example.Game"))
            db.warnedSubjectDao().insert(WarnedSubjectEntity("2026-07-03", "com.example.Game"))
            assertEquals(1, db.warnedSubjectDao().exists("2026-07-03", "com.example.Game"))
        }
    }
}
