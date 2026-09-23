package com.sbstravels.taximeter.meter

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

data class LiveMeterSnapshot(
    val distanceKm: Double,
    val waitingMinutes: Double,
    val fare: Double,
    val waiting: Boolean,
    val latitude: Double?,
    val longitude: Double?
)

class MeterEngine(
    private val context: Context,
    private val tripId: String,
    private val baseFare: Double = 75.0,
    private val perKm: Double = 28.0,
    private val waitingPerMinute: Double = 2.0,
    private val mode: String = "METER",
    private val hourlyRate: Double = 350.0,
    private val freeKmPerHour: Double = 10.0,
    private val excessKmRate: Double = 20.0,
    private val startedAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        private const val GPS_INTERVAL_MS = 2000L
        private const val NETWORK_INTERVAL_MS = 3000L
        private const val MAX_GAP_SECONDS = 30L
        private const val MAX_ACCEPTED_DELTA_M = 100.0
        private const val WAITING_SPEED_MPS = 1.0
        private const val WAITING_DRIFT_M = 3.0
        private const val MAX_DISTANCE_ACCURACY_M = 50f
    }

    private val prefs = context.getSharedPreferences("meter_queue", Context.MODE_PRIVATE)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var lastLocation: Location? = null
    private var distanceM = prefs.getFloat(tripId + "_distance_m", 0f).toDouble()
    private var waitingSeconds = prefs.getLong(tripId + "_waiting_s", 0L)
    private var lastCaptured = prefs.getLong(tripId + "_last_captured", 0L)
    private var lastElapsedRealtime = 0L
    private var sequence = maxOf(1, prefs.getInt(tripId + "_sequence", nextSequenceFromQueue()))
    private var stopped = false

    var onSnapshot: ((LiveMeterSnapshot) -> Unit)? = null

    private fun nextSequenceFromQueue(): Int {
        val a = JSONArray(prefs.getString(tripId, "[]") ?: "[]")
        var maxSeq = 0
        for (i in 0 until a.length()) {
            maxSeq = max(maxSeq, a.getJSONObject(i).optInt("sequence_no", -1))
        }
        return maxSeq + 1
    }

    @SuppressLint("MissingPermission")
    fun start() {
        stopped = false
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                process(location)
            }

            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        }

        val l = listener ?: return

        // Sample continuously even while stationary. A zero distance filter is
        // intentional: waiting detection must receive stationary GPS updates.
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_INTERVAL_MS,
                0f,
                l
            )
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                NETWORK_INTERVAL_MS,
                0f,
                l
            )
        }
    }

    fun stop() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
        stopped = true
    }

    fun queuedEvents() = JSONArray(prefs.getString(tripId, "[]") ?: "[]")

    fun clearQueuedEvents() {
        prefs.edit().remove(tripId).apply()
    }

    fun clearTripState() {
        prefs.edit()
            .remove(tripId)
            .remove(tripId + "_distance_m")
            .remove(tripId + "_waiting_s")
            .remove(tripId + "_last_captured")
            .remove(tripId + "_sequence")
            .apply()
    }

    private fun process(location: Location) {
        if (stopped) return

        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = lastLocation

        var rawDeltaM = 0.0
        var billableDistanceDeltaM = 0.0
        var waitingDeltaSeconds = 0L

        if (previous != null) {
            rawDeltaM = max(0.0, previous.distanceTo(location).toDouble())

            val dtSeconds = if (lastElapsedRealtime > 0L) {
                ((nowElapsed - lastElapsedRealtime).coerceAtLeast(0L) / 1000L)
            } else {
                0L
            }

            val reportedSpeed = if (location.hasSpeed()) {
                location.speed.toDouble().coerceAtLeast(0.0)
            } else {
                0.0
            }

            val derivedSpeed = if (dtSeconds > 0L) rawDeltaM / dtSeconds else 0.0
            val effectiveSpeed = if (location.hasSpeed()) reportedSpeed else derivedSpeed

            val validGap = dtSeconds in 1L..MAX_GAP_SECONDS
            val normalAccuracy = location.accuracy <= MAX_DISTANCE_ACCURACY_M

            // Only add distance when the sample represents actual movement.
            // Small stationary GPS drift is deliberately excluded from the fare.
            if (
                rawDeltaM < MAX_ACCEPTED_DELTA_M &&
                normalAccuracy &&
                (effectiveSpeed > WAITING_SPEED_MPS || rawDeltaM > WAITING_DRIFT_M)
            ) {
                billableDistanceDeltaM = rawDeltaM
                distanceM += rawDeltaM
            }

            // Waiting is time-based, not distance-based. A stationary sample
            // within the normal GPS drift envelope accrues the real elapsed
            // seconds, capped to avoid charging for a long GPS outage.
            if (
                validGap &&
                effectiveSpeed <= WAITING_SPEED_MPS &&
                rawDeltaM <= WAITING_DRIFT_M
            ) {
                waitingDeltaSeconds = dtSeconds
                waitingSeconds += dtSeconds
            }
        }

        lastLocation = location
        lastElapsedRealtime = nowElapsed
        if (lastCaptured == 0L) lastCaptured = nowWall

        val event = JSONObject()
            .put("client_event_id", UUID.randomUUID().toString())
            .put("sequence_no", sequence++)
            .put("captured_at", java.time.Instant.ofEpochMilli(nowWall).toString())
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy_m", location.accuracy)
            .put("speed_mps", if (location.hasSpeed()) location.speed else 0.0)
            .put("distance_delta_m", billableDistanceDeltaM)
            .put("waiting_delta_seconds", waitingDeltaSeconds)

        appendEvent(event)
        lastCaptured = nowWall

        prefs.edit()
            .putFloat(tripId + "_distance_m", distanceM.toFloat())
            .putLong(tripId + "_waiting_s", waitingSeconds)
            .putLong(tripId + "_last_captured", lastCaptured)
            .putInt(tripId + "_sequence", sequence)
            .apply()

        val distanceKm = distanceM / 1000.0
        val waitingMinutes = waitingSeconds / 60.0

        val fare = if (mode == "HOURLY") {
            val hours = max(
                1L,
                ((nowWall - startedAtMillis).coerceAtLeast(0L) + 3599999L) / 3600000L
            )
            hours * hourlyRate +
                max(0.0, distanceKm - hours * freeKmPerHour) * excessKmRate
        } else {
            baseFare +
                distanceKm * perKm +
                waitingMinutes * waitingPerMinute
        }

        onSnapshot?.invoke(
            LiveMeterSnapshot(
                distanceKm = distanceKm,
                waitingMinutes = waitingMinutes,
                fare = fare,
                waiting = waitingDeltaSeconds > 0L,
                latitude = location.latitude,
                longitude = location.longitude
            )
        )
    }

    private fun appendEvent(event: JSONObject) {
        val a = queuedEvents()
        a.put(event)
        prefs.edit().putString(tripId, a.toString()).apply()
    }
}
