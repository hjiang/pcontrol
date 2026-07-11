package com.pcontrol.app

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pcontrol.app.db.AppDatabase
import com.pcontrol.app.db.CachedPolicyEntity
import com.pcontrol.app.db.UsageCounterEntity
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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        Enforcer.resetWarnedSet()
        Enforcer.webBlockStrikes.resetAll()
        BrowserAccessibilityService.instance = null
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeCoordinator(): BlockingCoordinator {
        return BlockingCoordinator(context, db)
    }

    private suspend fun insertPolicy(pkg: String, dailyLimitMinutes: Int) {
        val policyJson = """
            {
              "version": 1,
              "warn_threshold_percent": 90,
              "limits": [
                {"kind": "app", "subject": "$pkg", "daily_limit_minutes": $dailyLimitMinutes}
              ],
              "exclusions": []
            }
        """.trimIndent()
        db.cachedPolicyDao().upsert(CachedPolicyEntity(version = 1, json = policyJson))
    }

    private suspend fun insertAppSeconds(pkg: String, seconds: Int) {
        db.usageCounterDao().upsert(
            UsageCounterEntity(
                day = dayKey(),
                kind = "app",
                subject = pkg,
                label = pkg,
                seconds = seconds,
                syncedSeconds = 0
            )
        )
    }

    private fun dayKey(): String {
        val zone = java.time.ZoneId.systemDefault()
        return com.pcontrol.core.UsageDay.currentKey(zone)
    }

    @Test
    fun `blocks app when per-app limit exceeded`() {
        val intentsStarted = mutableListOf<Intent>()
        runBlocking {
            insertPolicy("com.tencent.mm", 1)
            insertAppSeconds("com.tencent.mm", 120)
        }

        val blocked = runBlocking {
            makeCoordinator().checkAndEnforceApp("com.tencent.mm") { intent ->
                intentsStarted.add(intent)
                true
            }
        }

        assertEquals(true, blocked)
        assertEquals(1, intentsStarted.size)
    }

    @Test
    fun `does not block when under per-app limit`() {
        runBlocking {
            insertPolicy("com.tencent.mm", 10)
            insertAppSeconds("com.tencent.mm", 120)
        }

        val blocked = runBlocking {
            makeCoordinator().checkAndEnforceApp("com.tencent.mm") { true }
        }

        assertEquals(false, blocked)
    }

    @Test
    fun `does not block when no policy is cached`() {
        runBlocking {
            insertAppSeconds("com.tencent.mm", 600)
        }

        val blocked = runBlocking {
            makeCoordinator().checkAndEnforceApp("com.tencent.mm") { true }
        }

        assertEquals(false, blocked)
    }

    @Test
    fun `does not block never-block packages`() {
        val intentsStarted = mutableListOf<Intent>()
        runBlocking {
            insertPolicy("com.pcontrol.app", 1)
            insertAppSeconds("com.pcontrol.app", 600)
        }

        val blocked = runBlocking {
            makeCoordinator().checkAndEnforceApp("com.pcontrol.app") { intent ->
                intentsStarted.add(intent)
                true
            }
        }

        assertEquals(false, blocked)
        assertEquals(0, intentsStarted.size)
    }

    @Test
    fun `blocks when total daily limit exceeded`() {
        val intentsStarted = mutableListOf<Intent>()
        runBlocking {
            val policyJson = """
                {
                  "version": 1,
                  "warn_threshold_percent": 90,
                  "total_daily_limit_minutes": 1,
                  "limits": [],
                  "exclusions": []
                }
            """.trimIndent()
            db.cachedPolicyDao().upsert(CachedPolicyEntity(version = 1, json = policyJson))
            insertAppSeconds("com.tencent.mm", 120)
        }

        val blocked = runBlocking {
            makeCoordinator().checkAndEnforceApp("com.tencent.mm") { intent ->
                intentsStarted.add(intent)
                true
            }
        }

        assertEquals(true, blocked)
        assertEquals(1, intentsStarted.size)
    }

    @Test
    fun `does not block excluded apps when total limit exceeded`() {
        runBlocking {
            val policyJson = """
                {
                  "version": 1,
                  "warn_threshold_percent": 90,
                  "total_daily_limit_minutes": 1,
                  "limits": [],
                  "exclusions": [
                    {"kind": "app", "subject": "com.example.Edu"}
                  ]
                }
            """.trimIndent()
            db.cachedPolicyDao().upsert(CachedPolicyEntity(version = 1, json = policyJson))
            insertAppSeconds("com.example.Edu", 600)
        }

        val blocked = runBlocking {
            makeCoordinator().checkAndEnforceApp("com.example.Edu") { true }
        }

        assertEquals(false, blocked)
    }

    /**
     * Regression test for Bug 2: BlockedActivity self-masks enforcement.
     * When BlockedActivity (com.pcontrol.app) fires TYPE_WINDOW_STATE_CHANGED,
     * the accessibility service must not try to block it — com.pcontrol.app
     * is in BASE_NEVER_BLOCK_PACKAGES.
     */
    @Test
    fun `never blocks BlockedActivity package after enforcement`() {
        val intentsStarted = mutableListOf<Intent>()
        runBlocking {
            insertPolicy("com.tencent.mm", 1)
            insertAppSeconds("com.tencent.mm", 120)
        }

        // First: block WeChat
        runBlocking {
            makeCoordinator().checkAndEnforceApp("com.tencent.mm") { intent ->
                intentsStarted.add(intent)
                true
            }
        }
        assertEquals(1, intentsStarted.size)

        // Then: BlockedActivity fires TYPE_WINDOW_STATE_CHANGED for com.pcontrol.app
        // — must not be blocked (would cause infinite loop)
        val blocked = runBlocking {
            makeCoordinator().checkAndEnforceApp("com.pcontrol.app") { intent ->
                intentsStarted.add(intent)
                true
            }
        }

        assertEquals(false, blocked)
        assertEquals(1, intentsStarted.size)
    }
}