package com.example.hyundai_sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import triplog.FileStateStore
import triplog.PurposeClassifier
import triplog.ManualClassifier
import triplog.TripLogApp
import triplog.ReportDetailStore
import triplog.TripLogManager
import triplog.State
import triplog.Trip
import java.io.File

/** One instance per app process. Invoke manager IO off the UI thread.
 * The host supplies identity before invoking operations. Use the same manager for all screens.
 * JVM file storage is a prototype; replace StateStore with Room before production.
 */
object TripLogHost {
    @Volatile private var instance: TripLogManager? = null

    fun get(context: Context): TripLogManager = instance ?: synchronized(this) {
        instance ?: create(context, ManualClassifier()).also { instance = it }
    }

    @Volatile private var appInstance: TripLogApp? = null

    fun app(context: Context): TripLogApp = appInstance ?: synchronized(this) {
        appInstance ?: TripLogApp(get(context), details = ReportDetailStore(
            File(context.applicationContext.noBackupFilesDir, "triplog/state.bin.report.properties")
        )).also { appInstance = it }
    }

    internal const val DEMO_DRIVER = "demo-driver"
    private var demoInstance: TripLogApp? = null

    @Synchronized
    internal fun demoApp(context: Context, seed: (() -> List<Trip>)? = null): TripLogApp {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Demo history requires a debug build" }
        demoInstance?.let { return it }
        val file = File(context.applicationContext.noBackupFilesDir, "triplog-demo/state.bin")
        val store = FileStateStore(file)
        if (!file.exists()) {
            val initialTrips = checkNotNull(seed) { "Open demo history from the test app home first" }
            store.load()
            store.save(State(trips = initialTrips()))
        }
        return TripLogApp(
            TripLogManager(store, ManualClassifier()),
            details = ReportDetailStore(File(file.absolutePath + ".report.properties"))
        ).also { demoInstance = it }
    }

    private fun create(context: Context, classifier: PurposeClassifier): TripLogManager {
        // noBackupFilesDir: credentials/trip data are not copied by automatic device backup.
        val file = File(context.applicationContext.noBackupFilesDir, "triplog/state.bin")
        return TripLogManager(FileStateStore(file), classifier)
    }
}
