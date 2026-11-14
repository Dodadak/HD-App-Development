package com.example.hyundai_sdk

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import java.io.File
import triplog.TripLogUser

/** Entry from a trusted host in the same app. TripLogActivity is not exported. */
object TripLogScreen {
    private const val USER_ID = "triplog.user.id"
    private const val USER_NAME = "triplog.user.name"
    private const val USER_ADMIN = "triplog.user.admin"

    fun intent(context: Context, user: TripLogUser): Intent =
        Intent(context, TripLogActivity::class.java)
            .putExtra(USER_ID, user.id)
            .putExtra(USER_NAME, user.displayName)
            .putExtra(USER_ADMIN, user.isAdmin)

    internal fun readUser(context: Context, intent: Intent): TripLogUser? {
        if (!intent.hasExtra(USER_ID) && !intent.hasExtra(USER_NAME) && !intent.hasExtra(USER_ADMIN)) {
            return readDemoUser(context)
        }
        return runCatching {
            TripLogUser(
                requireNotNull(intent.getStringExtra(USER_ID)),
                requireNotNull(intent.getStringExtra(USER_NAME)),
                intent.getBooleanExtra(USER_ADMIN, false)
            )
        }.getOrNull()
    }

    // Standalone demo launcher stages this file in app-private storage.
    internal fun readDemoUser(context: Context): TripLogUser? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return runCatching {
            val json = JSONObject(File(context.filesDir, "triplog-demo-user.json").readText())
            TripLogUser(json.getString("id"), json.getString("displayName"), json.getBoolean("isAdmin"))
        }.getOrNull()
    }
}
