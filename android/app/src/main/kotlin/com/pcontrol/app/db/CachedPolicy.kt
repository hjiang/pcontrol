package com.pcontrol.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_policy")
data class CachedPolicyEntity(
    @PrimaryKey
    val id: Int = 1,           // singleton row
    val version: Int,          // policy_version from server
    val json: String           // full policy JSON blob
)
