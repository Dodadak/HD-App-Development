package com.example.hyundai_sdk.navigation

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** A route estimate is valid for one log entry, even when the destination spans several trips. */
internal class NavigationEstimate(val meters: Int) {
    // Mutated only by the single TripLog worker.
    var claimed = false
}

internal data class NavigationSnapshot(
    val destination: String? = null,
    val remainingMeters: Int? = null,
    val estimate: NavigationEstimate? = null,
    val unavailable: Boolean = false
)

/** Read-only SDK session. All state and listener delivery are confined to the main looper. */
internal class PleosNavigationSession(
    context: Context,
    private val onUpdate: (NavigationSnapshot) -> Unit
) : AutoCloseable {
    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var connected = false
    private var foreground = false
    private var guiding = false
    private var snapshot = NavigationSnapshot()
    private var stagedEstimate: NavigationEstimate? = null
    private var stagedAt = 0L
    private var routeSearchCompleted = false
    private var destinationAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (!foreground || !connected || closed) return
            if (snapshot.destination != null && SystemClock.elapsedRealtime() - destinationAt > 15_000) {
                publish(snapshot.copy(destination = null, remainingMeters = null))
            }
            query { helper.getRouteStateInfo() }
            main.postDelayed(this, 5_000)
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = deliver {
            // The SDK describes this as route-search start. Wait for confirmed guidance.
            stagedEstimate = routeStartInfo.distance.takeIf { it > 0 }?.let(::NavigationEstimate)
            stagedAt = SystemClock.elapsedRealtime()
            routeSearchCompleted = false
            publish(NavigationSnapshot())
        }

        override fun onRoutingCompleted() = deliver {
            routeSearchCompleted = true
            query { helper.getRouteStateInfo() }
        }

        override fun onReRouteEnded() = deliver { query { helper.getRouteStateInfo() } }

        override fun onDestinationChanged() = deliver {
            publish(snapshot.copy(destination = null, remainingMeters = null))
            query { helper.getRouteStateInfo() }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = deliver {
            updateGuidance(routeStateInfo.termsAgreement && routeStateInfo.isRouting)
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) = deliver {
            when (navigationStatus) {
                NavigationStatus.ROUTE_DRIVE -> {
                    routeSearchCompleted = true
                    updateGuidance(true)
                }
                NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> updateGuidance(false)
                NavigationStatus.INITIALIZING -> Unit
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = deliver {
            if (guiding && destinationInfo.name.isNotBlank()) {
                destinationAt = SystemClock.elapsedRealtime()
                publish(snapshot.copy(
                    destination = destinationInfo.name.trim(),
                    remainingMeters = destinationInfo.remainDistance.takeIf { it >= 0 },
                    unavailable = false
                ))
            }
        }

        override fun onRouteCancelled() = deliver { endRoute() }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            deliver { endRoute() }

        override fun onError(errorCode: NaviErrorCode) = deliver {
            Log.w(TAG, "NaviHelper error: $errorCode")
            endRoute(unavailable = true)
        }
    }

    fun start() {
        try {
            helper.initialize(
                onConnected = { deliver {
                    connected = true
                    query {
                        helper.addListener(listener)
                        helper.getRouteStateInfo()
                    }
                    schedulePoll()
                } },
                onFailed = { error -> deliver { unavailable(error) } }
            )
        } catch (error: Exception) {
            unavailable(error)
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        schedulePoll()
    }

    private fun schedulePoll() {
        main.removeCallbacks(poll)
        if (foreground && connected) main.post(poll)
    }

    private fun updateGuidance(active: Boolean) {
        guiding = active
        if (!active) {
            publish(NavigationSnapshot())
            return
        }
        if (SystemClock.elapsedRealtime() - stagedAt > 60_000) stagedEstimate = null
        val estimate = if (routeSearchCompleted) stagedEstimate else null
        if (estimate != null) stagedEstimate = null
        publish(snapshot.copy(estimate = estimate ?: snapshot.estimate, unavailable = false))
        query { helper.getDestinationInfo() }
    }

    private fun endRoute(unavailable: Boolean = false) {
        guiding = false
        stagedEstimate = null
        publish(NavigationSnapshot(unavailable = unavailable))
    }

    private fun unavailable(error: Throwable) {
        Log.w(TAG, "Unable to read built-in navigation", error)
        endRoute(unavailable = true)
    }

    private fun query(action: () -> Unit) {
        if (!connected || closed) return
        try {
            action()
        } catch (error: Exception) {
            unavailable(error)
        }
    }

    private fun publish(next: NavigationSnapshot) {
        if (next == snapshot) return
        snapshot = next
        onUpdate(next)
    }

    private fun deliver(action: () -> Unit) {
        main.post { if (!closed) action() }
    }

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        try {
            if (connected) helper.removeListener(listener)
        } finally {
            helper.release()
        }
    }

    private companion object {
        const val TAG = "TripLogNavigation"
    }
}
