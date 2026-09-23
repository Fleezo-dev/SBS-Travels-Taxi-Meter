package com.sbstravels.taximeter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sbstravels.taximeter.meter.LiveMeterSnapshot
import com.sbstravels.taximeter.meter.MeterEngine
import org.json.JSONObject

class MeterForegroundService : Service() {
    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_BASE_FARE = "base_fare"
        const val EXTRA_PER_KM = "per_km"
        const val EXTRA_WAITING_PER_MINUTE = "waiting_per_minute"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HOURLY_RATE = "hourly_rate"
        const val EXTRA_FREE_KM_PER_HOUR = "free_km_per_hour"
        const val EXTRA_EXCESS_KM_RATE = "excess_km_rate"
        const val EXTRA_STARTED_AT = "started_at"
        const val PREFS_NAME = "meter_queue"
        const val SNAPSHOT_SUFFIX = "_snapshot"
        private const val CHANNEL_ID = "sbs_meter"
        private const val NOTIFICATION_ID = 2001
    }

    private var engine: MeterEngine? = null
    private var tripId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SBS Travels meter running")
            .setContentText("GPS meter is active in the background")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_TRIP_ID) ?: return START_NOT_STICKY
        tripId = id
        engine?.stop()
        val e = MeterEngine(
            this,
            id,
            intent.getDoubleExtra(EXTRA_BASE_FARE, 75.0),
            intent.getDoubleExtra(EXTRA_PER_KM, 28.0),
            intent.getDoubleExtra(EXTRA_WAITING_PER_MINUTE, 2.0),
            intent.getStringExtra(EXTRA_MODE) ?: "METER",
            intent.getDoubleExtra(EXTRA_HOURLY_RATE, 350.0),
            intent.getDoubleExtra(EXTRA_FREE_KM_PER_HOUR, 10.0),
            intent.getDoubleExtra(EXTRA_EXCESS_KM_RATE, 20.0),
            intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
        )
        e.onSnapshot = { s -> saveSnapshot(id, s) }
        engine = e
        e.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveSnapshot(id: String, s: LiveMeterSnapshot) {
        val o = JSONObject()
            .put("distance_km", s.distanceKm)
            .put("waiting_minutes", s.waitingMinutes)
            .put("fare", s.fare)
            .put("waiting", s.waiting)
            .put("latitude", s.latitude)
            .put("longitude", s.longitude)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(id + SNAPSHOT_SUFFIX, o.toString())
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Taxi Meter",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
