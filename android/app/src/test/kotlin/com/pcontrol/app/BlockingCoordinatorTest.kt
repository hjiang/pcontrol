package com.pcontrol.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.CachedPolicyEntity
import com.pcontrol.app.db.UsageCounterEntity
import com.pcontrol.core.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BlockingCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        Enforcer.resetWarnedSet()
        Enforcer.webBlockStrikes.resetAll()
    }

    @After fun tearDown() = db.close()

    private fun day() = com.pcontrol.core.UsageDay.currentKey(java.time.ZoneId.systemDefault())

    private suspend fun policy(json: String) {
        db.cachedPolicyDao().upsert(CachedPolicyEntity(version = 1, json = json.trimIndent()))
    }

    private suspend fun appSeconds(pkg: String, seconds: Int) {
        db.usageCounterDao().upsert(UsageCounterEntity(day(), "app", pkg, pkg, seconds, 0))
    }

    @Test
    fun `over-limit app returns a request rather than presenting anything`() = runBlocking {
        policy("""
            {"version":1,"warn_threshold_percent":90,
             "limits":[{"kind":"app","subject":"com.tencent.mm","daily_limit_minutes":1}],
             "exclusions":[]}
        """)
        appSeconds("com.tencent.mm", 120)

        val result = BlockingCoordinator(context, db).evaluateForeground("com.tencent.mm", null)
        assertEquals(Verdict.BLOCK_APP, result.appVerdict)
        assertEquals("com.tencent.mm", result.appRequest?.subject)
        assertEquals(null, result.webRequest)
    }

    @Test
    fun `under-limit and missing policy return no presentation request`() = runBlocking {
        appSeconds("com.tencent.mm", 120)
        assertEquals(null, BlockingCoordinator(context, db)
            .evaluateForeground("com.tencent.mm", null).appRequest)

        policy("""
            {"version":1,"warn_threshold_percent":90,
             "limits":[{"kind":"app","subject":"com.tencent.mm","daily_limit_minutes":10}],
             "exclusions":[]}
        """)
        assertEquals(null, BlockingCoordinator(context, db)
            .evaluateForeground("com.tencent.mm", null).appRequest)
    }

    @Test
    fun `never-block pcontrol package cannot produce a request`() = runBlocking {
        policy("""
            {"version":1,"warn_threshold_percent":90,
             "limits":[{"kind":"app","subject":"com.pcontrol.app","daily_limit_minutes":1}],
             "exclusions":[]}
        """)
        appSeconds("com.pcontrol.app", 120)
        val result = BlockingCoordinator(context, db).evaluateForeground("com.pcontrol.app", null)
        assertEquals(Verdict.ALLOW, result.appVerdict)
        assertEquals(null, result.appRequest)
    }
}
