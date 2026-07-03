package com.pcontrol.app.db

import androidx.room.Entity

@Entity(tableName = "usage_counter", primaryKeys = ["day", "kind", "subject"])
data class UsageCounterEntity(
    val day: String,          // "YYYY-MM-DD"
    val kind: String,         // "app" or "web"
    val subject: String,      // package name or registrable domain
    val label: String,        // human-readable name
    val seconds: Int,         // total tracked seconds
    val syncedSeconds: Int    // seconds already sent to server
)
