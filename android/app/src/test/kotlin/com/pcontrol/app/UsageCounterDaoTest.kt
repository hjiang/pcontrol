package com.pcontrol.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.UsageCounterEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageCounterDaoTest {

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
    fun `markSynced with sentSeconds preserves unsynced delta from mid-request tick`() {
        val dao = db.usageCounterDao()

        runBlocking {
            // Insert counter with seconds=50, synced=0
            dao.upsert(UsageCounterEntity(
                day = "2026-07-03",
                kind = "app",
                subject = "com.example.Game",
                label = "Game",
                seconds = 50,
                syncedSeconds = 0
            ))

            // Simulate a tick to 60 (recorded after the snapshot was built)
            dao.upsert(UsageCounterEntity(
                day = "2026-07-03",
                kind = "app",
                subject = "com.example.Game",
                label = "Game",
                seconds = 60,
                syncedSeconds = 0
            ))

            // Mark synced with the snapshot value (50), NOT current seconds (60)
            dao.markSynced("2026-07-03", "app", "com.example.Game", sentSeconds = 50)

            // getUnsynced() should still return the row with delta = 60 - 50 = 10
            val unsynced = dao.getUnsynced()
            assertEquals(1, unsynced.size)
            val row = unsynced.first()
            assertEquals("com.example.Game", row.subject)
            assertEquals(60, row.seconds)
            assertEquals(50, row.syncedSeconds)
        }
    }

    @Test
    fun `markSynced with current seconds marks everything synced`() {
        val dao = db.usageCounterDao()

        runBlocking {
            dao.upsert(UsageCounterEntity(
                day = "2026-07-03",
                kind = "app",
                subject = "com.example.Game",
                label = "Game",
                seconds = 50,
                syncedSeconds = 0
            ))

            // No tick happened — mark synced with full value
            dao.markSynced("2026-07-03", "app", "com.example.Game", sentSeconds = 50)

            val unsynced = dao.getUnsynced()
            assertEquals(0, unsynced.size)
        }
    }
}
