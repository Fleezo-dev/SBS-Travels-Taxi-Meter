package com.sbstravels.taximeter.meter

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
    private val waitingPerMinute: Double = 2.0
) {
    private val prefs=context.getSharedPreferences("meter_queue",Context.MODE_PRIVATE)
    private val locationManager=context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener?=null
    private var lastLocation: Location?=null
    private var distanceM=0.0
    private var waitingSeconds=0L
    private var lastCaptured=0L
    private var sequence=0
    private var stopped=false
    var onSnapshot:((LiveMeterSnapshot)->Unit)?=null

    @SuppressLint("MissingPermission")
    fun start() {
        stopped=false
        listener=object:LocationListener{
            override fun onLocationChanged(location:Location){ process(location) }
            override fun onProviderDisabled(provider:String){}
            override fun onProviderEnabled(provider:String){}
            override fun onStatusChanged(provider:String,status:Int,extras:Bundle){}
        }
        val l=listener ?: return
        if(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000L,5f,l)
        else if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000L,10f,l)
    }

    fun stop() {
        stopped=true
        listener?.let { locationManager.removeUpdates(it) }
        listener=null
    }

    fun queuedEvents():JSONArray = JSONArray(prefs.getString(tripId,"[]")?:"[]")

    fun clearQueuedEvents(){prefs.edit().remove(tripId).apply()}

    fun appendEvent(event:JSONObject) {
        val a=queuedEvents(); a.put(event); prefs.edit().putString(tripId,a.toString()).apply()
    }

    fun applySyncSuccess(accepted:Int) {
        if(accepted<=0)return
        val a=queuedEvents()
        val keep=JSONArray()
        for(i in accepted until a.length()) keep.put(a.getJSONObject(i))
        prefs.edit().putString(tripId,keep.toString()).apply()
    }

    private fun process(location:Location) {
        if(stopped)return
        val now=System.currentTimeMillis()
        val previous=lastLocation
        var delta=0.0
        if(previous!=null){
            delta=max(0.0,previous.distanceTo(location).toDouble())
            if(delta<100.0) distanceM+=delta
            val dt=((now-lastCaptured).coerceAtLeast(0L))/1000L
            val speed=if(location.hasSpeed())location.speed.toDouble() else if(dt>0) delta/dt else 0.0
            if(speed<=1.0 && dt in 1..30) waitingSeconds+=dt
        }
        lastLocation=location
        if(lastCaptured==0L) lastCaptured=now
        val elapsed=(now-lastCaptured).coerceAtLeast(0L)/1000L
        val waiting=waitingSeconds>=60L && (location.speed<=1f || !location.hasSpeed())
        val event=JSONObject()
            .put("client_event_id",UUID.randomUUID().toString())
            .put("sequence_no",sequence++)
            .put("captured_at",java.time.Instant.ofEpochMilli(now).toString())
            .put("latitude",location.latitude).put("longitude",location.longitude)
            .put("accuracy_m",location.accuracy)
            .put("speed_mps",if(location.hasSpeed())location.speed else 0.0)
            .put("distance_delta_m",delta)
            .put("waiting_delta_seconds",if(previous==null)0 else elapsed.coerceAtMost(30L))
        appendEvent(event)
        lastCaptured=now
        val d=distanceM/1000.0
        val w=waitingSeconds/60.0
        onSnapshot?.invoke(LiveMeterSnapshot(d,w,baseFare+d*perKm+w*waitingPerMinute,waiting,location.latitude,location.longitude))
    }
}