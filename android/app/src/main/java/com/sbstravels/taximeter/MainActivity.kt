package com.sbstravels.taximeter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbstravels.taximeter.data.Session
import com.sbstravels.taximeter.data.SupabaseClient
import com.sbstravels.taximeter.meter.LiveMeterSnapshot
import com.sbstravels.taximeter.meter.MeterEngine
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            meterPermissionGranted = true
        } else {
            meterPermissionGranted = false
        }
    }
    private var meterPermissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        meterPermissionGranted =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        setContent { MaterialTheme { TaxiApp() } }
    }

    @Composable
    private fun TaxiApp() {
        var session by remember { mutableStateOf<Session?>(null) }
        var activated by remember { mutableStateOf(false) }
        var trips by remember { mutableStateOf(JSONArray()) }
        var activeTrip by remember { mutableStateOf<JSONObject?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }

        fun work(block: () -> Unit) {
            busy = true; message = ""
            Thread {
                try { block() }
                catch (e: Exception) { runOnUiThread { message = e.message ?: "Operation failed" } }
                finally { runOnUiThread { busy = false } }
            }.start()
        }

        when {
            session == null -> LoginScreen(busy, message) { email, pw ->
                work {
                    val s = SupabaseClient.login(email, pw)
                    runOnUiThread { session = s; currentSession = s }
                }
            }
            !activated -> ActivationScreen(busy, message) { code ->
                val s = session!!
                work {
                    SupabaseClient.activate(s, this@MainActivity, code)
                    runOnUiThread { activated = true }
                }
            }
            activeTrip != null -> MeterScreen(
                session = session!!,
                trip = activeTrip!!,
                permissionGranted = meterPermissionGranted,
                onRequestPermission = {
                    locationPermission.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                },
                onBack = { activeTrip = null },
                onCompleted = {
                    activeTrip = null
                    work {
                        val o = SupabaseClient.trips(session!!)
                        runOnUiThread { trips = o.optJSONArray("trips") ?: JSONArray() }
                    }
                }
            )
            else -> Dashboard(trips, busy, message, onRefresh = {
                work {
                    val o = SupabaseClient.trips(session!!)
                    runOnUiThread { trips = o.optJSONArray("trips") ?: JSONArray() }
                }
            }, onOpenMeter = { activeTrip = it })
        }
    }

    @Composable
    private fun LoginScreen(busy: Boolean, message: String, onLogin: (String, String) -> Unit) {
        var email by remember { mutableStateOf("") }
        var pw by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("SBS Travels", style = MaterialTheme.typography.headlineMedium)
            Text("Taxi Meter • Driver App", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(email, { email = it }, label = { Text("Driver email") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Button({ onLogin(email, pw) }, enabled = !busy && email.isNotBlank() && pw.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Signing in…" else "Sign in")
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
    }

    @Composable
    private fun ActivationScreen(busy: Boolean, message: String, onActivate: (String) -> Unit) {
        var code by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Driver Activation", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Activation is mandatory. This device must be approved before trips or the meter can be used.")
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(code, { code = it }, label = { Text("Driver activation code") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Button({ onActivate(code) }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Activating…" else "Activate Device")
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
    }

    @Composable
    private fun Dashboard(
        trips: JSONArray, busy: Boolean, message: String,
        onRefresh: () -> Unit, onOpenMeter: (JSONObject) -> Unit
    ) {
        LaunchedEffect(Unit) { onRefresh() }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Assigned Trips", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            if (trips.length() == 0 && !busy) Text("No active trips", modifier = Modifier.padding(top = 30.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                for (i in 0 until trips.length()) TripCard(trips.getJSONObject(i), onRefresh, onOpenMeter)
            }
        }
    }

    @Composable
    private fun TripCard(t: JSONObject, onRefresh: () -> Unit, onOpenMeter: (JSONObject) -> Unit) {
        var localBusy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        val status = t.optString("status")
        val next = when (status) {
            "ASSIGNED" -> "ACCEPTED"
            "ACCEPTED" -> "STARTED"
            else -> null
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t.optString("customer_name", "Customer") + " • " + status, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(t.optString("pickup_address", "Pickup"))
                Text("→ " + t.optString("destination_address", "Destination"))
                if (!t.isNull("total_fare")) Text("Current fare ₹" + t.optDouble("total_fare", 0.0))
                Spacer(Modifier.height(8.dp))
                if (status == "STARTED" || status == "RUNNING" || status == "WAITING") {
                    Button(onClick = { onOpenMeter(t) }, modifier = Modifier.fillMaxWidth()) { Text("Open Meter") }
                } else if (next != null) {
                    Button(enabled = !localBusy, onClick = {
                        localBusy = true
                        Thread {
                            try {
                                SupabaseClient.transition(currentSession!!, t.getString("id"), next)
                                if (next == "STARTED") runOnUiThread { onOpenMeter(t.put("status", "STARTED")) }
                                else runOnUiThread { onRefresh() }
                            } catch (e: Exception) { err = e.message ?: "Trip update failed" }
                            finally { localBusy = false }
                        }.start()
                    }) { Text(if (localBusy) "Updating…" else if (next == "ACCEPTED") "Accept Trip" else "Start Trip") }
                }
                if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    @Composable
    private fun MeterScreen(
        session: Session, trip: JSONObject, permissionGranted: Boolean,
        onRequestPermission: () -> Unit, onBack: () -> Unit, onCompleted: () -> Unit
    ) {
        var snapshot by remember { mutableStateOf(LiveMeterSnapshot(0.0, 0.0, 75.0, false, null, null)) }
        var syncMessage by remember { mutableStateOf("Meter ready") }
        var completing by remember { mutableStateOf(false) }
        val engine = remember { MeterEngine(this@MainActivity, trip.getString("id")) }

        LaunchedEffect(permissionGranted) {
            if (permissionGranted) {
                if (trip.optString("status") == "STARTED") {
                    try { SupabaseClient.transition(session, trip.getString("id"), "RUNNING") } catch (_: Exception) {}
                }
                engine.onSnapshot = { s -> runOnUiThread { snapshot = s } }
                engine.start()
            }
        }
        DisposableEffect(Unit) { onDispose { engine.stop() } }

        fun sync() {
            Thread {
                try {
                    val accepted = SupabaseClient.ingestMeterEvents(session, trip.getString("id"), engine.queuedEvents())
                    if (accepted >= 0) engine.clearQueuedEvents()
                    runOnUiThread { syncMessage = "Synced $accepted meter events" }
                } catch (e: Exception) { runOnUiThread { syncMessage = "Offline: events kept on device" } }
            }.start()
        }

        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LIVE METER", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack, enabled = !completing) { Text("Back") }
            }
            if (!permissionGranted) {
                Spacer(Modifier.height(24.dp))
                Text("Location permission is required for the GPS meter.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("Enable GPS") }
            } else {
                Spacer(Modifier.height(20.dp))
                Text("₹" + String.format("%.2f", snapshot.fare), style = MaterialTheme.typography.displaySmall)
                Text(String.format("%.2f km", snapshot.distanceKm), style = MaterialTheme.typography.headlineMedium)
                Text(String.format("%.1f min waiting", snapshot.waitingMinutes))
                Text(if (snapshot.waiting) "WAITING" else "MOVING", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                Text(syncMessage)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { sync() }, modifier = Modifier.fillMaxWidth()) { Text("Sync Meter Data") }
                Spacer(Modifier.height(10.dp))
                Button(enabled = !completing, onClick = {
                    completing = true
                    Thread {
                        try {
                            val queued = engine.queuedEvents()
                            if (queued.length() > 0) {
                                val accepted = SupabaseClient.ingestMeterEvents(session, trip.getString("id"), queued)
                                engine.clearQueuedEvents()
                            }
                            SupabaseClient.transition(session, trip.getString("id"), "COMPLETED")
                            runOnUiThread { onCompleted() }
                        } catch (e: Exception) {
                            runOnUiThread { syncMessage = e.message ?: "Unable to complete trip"; completing = false }
                        }
                    }.start()
                }, modifier = Modifier.fillMaxWidth()) { Text(if (completing) "Completing…" else "Complete Trip") }
            }
        }
    }

    companion object { var currentSession: Session? = null }
}