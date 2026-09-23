package com.sbstravels.taximeter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.content.Intent
import androidx.core.content.FileProvider
import com.sbstravels.taximeter.invoice.InvoicePdf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.sbstravels.taximeter.data.Session
import com.sbstravels.taximeter.data.SupabaseClient
import com.sbstravels.taximeter.meter.LiveMeterSnapshot
import com.sbstravels.taximeter.meter.MeterEngine
import org.json.JSONArray
import org.json.JSONObject


@Composable
private fun SBSTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFFD91E18),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD6),
        onPrimaryContainer = Color(0xFF410002),
        secondary = Color(0xFF5F5F5F),
        background = Color(0xFFF7F7F7),
        surface = Color.White,
        surfaceVariant = Color(0xFFF0F0F0),
        error = Color(0xFFB3261E)
    )
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp)
        ),
        content = content
    )
}

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
        window.statusBarColor = android.graphics.Color.rgb(185, 28, 28)
        window.navigationBarColor = android.graphics.Color.rgb(18, 18, 18)
        setContent { SBSTheme { TaxiApp() } }
    }

    @Composable
    private fun TaxiApp() {
        var session by remember { mutableStateOf<Session?>(SupabaseClient.loadSession(this@MainActivity)) }
        LaunchedEffect(Unit) { currentSession = session }
        var activated by remember { mutableStateOf(false) }
        LaunchedEffect(session) { session?.let { s -> Thread { try { val ss = try { SupabaseClient.driverSession(s) } catch (_: Exception) { val refreshed = SupabaseClient.refresh(s); SupabaseClient.saveSession(this@MainActivity, refreshed); runOnUiThread { session = refreshed }; SupabaseClient.driverSession(refreshed) }; val d = ss.optJSONObject("driver"); runOnUiThread { activated = d?.optBoolean("activation_required", true) == false } } catch (_: Exception) {} }.start() } }
        var trips by remember { mutableStateOf(JSONArray()) }
        var activeTrip by remember { mutableStateOf<JSONObject?>(null) }
        var completedInvoice by remember { mutableStateOf<JSONObject?>(null) }
        var history by remember { mutableStateOf(JSONArray()) }
        var showHistory by remember { mutableStateOf(false) }\n        var showQuickMeter by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var lastBackPressedAt by remember { mutableLongStateOf(0L) }

        BackHandler(enabled = session != null && activated && !busy) {
            when {
                showHistory -> showHistory = false\n                showQuickMeter -> showQuickMeter = false
                completedInvoice != null -> completedInvoice = null
                activeTrip != null -> activeTrip = null
                else -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressedAt < 1800L) finish()
                    else {
                        lastBackPressedAt = now
                        Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

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
                    SupabaseClient.saveSession(this@MainActivity, s)
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
            showHistory -> HistoryScreen(history, onBack = { showHistory = false })\n            showQuickMeter -> QuickMeterScreen(session!!, onBack = { showQuickMeter = false }, onStarted = { trip -> showQuickMeter = false; activeTrip = trip })
            completedInvoice != null -> InvoiceScreen(
                invoice = completedInvoice!!,
                onDone = { completedInvoice = null }
            )
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
                onCompleted = { invoice ->
                    activeTrip = null
                    completedInvoice = invoice
                    work {
                        val o = SupabaseClient.trips(session!!)
                        runOnUiThread { trips = o.optJSONArray("trips") ?: JSONArray() }
                    }
                }
            )
            else -> Dashboard(session!!, trips, busy, message, onHistory = {
                work {
                    val o = SupabaseClient.history(session!!)
                    runOnUiThread { history = o.optJSONArray("trips") ?: JSONArray(); showHistory = true }
                }
            }, onRefresh = {
                work {
                    val o = SupabaseClient.trips(session!!)
                    runOnUiThread { trips = o.optJSONArray("trips") ?: JSONArray() }
                }
            }, onOpenMeter = { activeTrip = it }, onQuickMeter = { showQuickMeter = true })
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
        session: Session, trips: JSONArray, busy: Boolean, message: String,
        onHistory: () -> Unit, onRefresh: () -> Unit, onOpenMeter: (JSONObject) -> Unit, onQuickMeter: () -> Unit
    ) {
        var loadOtp by remember { mutableStateOf("") }
        var loadBusy by remember { mutableStateOf(false) }
        var loadMessage by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            onRefresh()
            while (true) { kotlinx.coroutines.delay(15000); onRefresh() }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Assigned Trips", style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = onHistory, enabled = !busy) { Text("History") }
                    TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }\n                    TextButton(onClick = onQuickMeter, enabled = !busy) { Text("Quick Meter") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)

            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Load Assigned Trip", style = MaterialTheme.typography.titleMedium)
                    Text("Enter the 6-digit load OTP given by the dispatcher. This is separate from the customer's start OTP.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = loadOtp,
                        onValueChange = { loadOtp = it.filter(Char::isDigit).take(6); loadMessage = "" },
                        label = { Text("Trip Load OTP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !loadBusy && loadOtp.length == 6,
                        onClick = {
                            loadBusy = true
                            loadMessage = ""
                            Thread {
                                try {
                                    SupabaseClient.loadTrip(session, loadOtp)
                                    runOnUiThread {
                                        loadOtp = ""
                                        loadMessage = "Trip loaded successfully."
                                        onRefresh()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { loadMessage = e.message ?: "Unable to load trip" }
                                } finally {
                                    runOnUiThread { loadBusy = false }
                                }
                            }.start()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (loadBusy) "Loading…" else "Load Trip") }
                    if (loadMessage.isNotBlank()) {
                        Text(
                            loadMessage,
                            color = if (loadMessage.startsWith("Trip loaded")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            if (trips.length() == 0 && !busy) Text("No active trips", modifier = Modifier.padding(top = 16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                for (i in 0 until trips.length()) TripCard(trips.getJSONObject(i), onRefresh, onOpenMeter)
            }
        }
    }

    @Composable
    private fun HistoryScreen(history: JSONArray, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Trip History", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Back") }
            }
            if (history.length() == 0) {
                Text("No completed or cancelled trips.", modifier = Modifier.padding(top = 30.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                    for (i in 0 until history.length()) {
                        val t = history.getJSONObject(i)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(t.optString("customer_name", "Customer") + " • " + t.optString("status"))
                                Text(t.optString("pickup_address", "Pickup") + " → " + t.optString("destination_address", "Destination"))
                                Text(String.format("%.2f km • ₹%.2f", t.optDouble("distance_km", 0.0), t.optDouble("total_fare", 0.0)))
                            }
                        }
                    }
                }
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
                    var otp by remember { mutableStateOf("") }
                    if (next == "STARTED") {
                        OutlinedTextField(
                            value = otp,
                            onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                            label = { Text("Customer start OTP") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(enabled = !localBusy && (next != "STARTED" || otp.length == 6), onClick = {
                        localBusy = true
                        Thread {
                            try {
                                SupabaseClient.transition(currentSession!!, t.getString("id"), next, if (next == "STARTED") otp else null)
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
        onRequestPermission: () -> Unit, onBack: () -> Unit, onCompleted: (JSONObject) -> Unit
    ) {
        var snapshot by remember { mutableStateOf(LiveMeterSnapshot(0.0, 0.0, 75.0, false, null, null)) }
        var syncMessage by remember { mutableStateOf("Meter ready") }
        var completing by remember { mutableStateOf(false) }
        val tariff = trip.optJSONObject("tariff")
        val tariffSnapshot = trip.optJSONObject("tariff_snapshot")
        val rules = tariffSnapshot?.optJSONObject("rules") ?: tariff?.optJSONObject("rules") ?: JSONObject()
        val mode = tariffSnapshot?.optString("mode")?.takeIf { !it.isNullOrBlank() } ?: tariff?.optString("mode","METER") ?: "METER"
        val baseFare = rules.optDouble("base_fare",75.0)
        val perKm = rules.optDouble("per_km",28.0)
        val waitingPerMinute = rules.optDouble("waiting_per_minute",2.0)
        val hourlyRate = rules.optDouble("hourly_rate",350.0)
        val freeKmPerHour = rules.optDouble("free_km_per_hour",10.0)
        val excessKmRate = rules.optDouble("excess_km_rate",20.0)
        val startedAtMillis = try { java.time.Instant.parse(trip.optString("started_at")).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
        val engine = remember(trip.getString("id")) {
            MeterEngine(this@MainActivity, trip.getString("id"), baseFare, perKm, waitingPerMinute, mode, hourlyRate, freeKmPerHour, excessKmRate, startedAtMillis)
        }

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
                Text(mode + " • ₹" + String.format("%.2f", snapshot.fare), style = MaterialTheme.typography.displaySmall)
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
                                SupabaseClient.ingestMeterEvents(session, trip.getString("id"), queued)
                                engine.clearQueuedEvents()
                            }
                            val result = SupabaseClient.completeTrip(session, trip.getString("id"))
                            val invoice = result.optJSONObject("invoice") ?: JSONObject()
                            runOnUiThread { onCompleted(invoice) }
                        } catch (e: Exception) {
                            runOnUiThread { syncMessage = e.message ?: "Unable to complete trip"; completing = false }
                        }
                    }.start()
                }, modifier = Modifier.fillMaxWidth()) { Text(if (completing) "Completing…" else "Complete Trip") }
            }
        }
    }



    @Composable
    private fun QuickMeterScreen(session: Session, onBack: () -> Unit, onStarted: (JSONObject) -> Unit) {
        var tariffs by remember { mutableStateOf(JSONArray()) }
        var customerName by remember { mutableStateOf("") }
        var selectedTariff by remember { mutableStateOf<JSONObject?>(null) }
        var loading by remember { mutableStateOf(true) }
        var starting by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            Thread {
                try {
                    val o = SupabaseClient.quickMeterTariffs(session)
                    val a = o.optJSONArray("tariffs") ?: JSONArray()
                    runOnUiThread {
                        tariffs = a
                        if (a.length() > 0) selectedTariff = a.getJSONObject(0)
                        loading = false
                    }
                } catch (e: Exception) {
                    runOnUiThread { error = e.message ?: "Unable to load tariffs"; loading = false }
                }
            }.start()
        }

        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("QUICK METER", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack, enabled = !starting) { Text("Back") }
            }
            Text("Start a direct customer meter without dispatcher assignment.", modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = customerName,
                onValueChange = { customerName = it },
                label = { Text("Customer name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Text("Tariff", style = MaterialTheme.typography.titleMedium)
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            } else if (tariffs.length() == 0) {
                Text("No active meter tariffs available.", modifier = Modifier.padding(top = 10.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    for (i in 0 until tariffs.length()) {
                        val tariff = tariffs.getJSONObject(i)
                        val selected = selectedTariff?.optString("id") == tariff.optString("id")
                        Card(
                            onClick = { selectedTariff = tariff },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(tariff.optString("name", "Meter Tariff"), style = MaterialTheme.typography.titleMedium)
                                    Text(tariff.optString("mode", "METER"))
                                }
                                RadioButton(selected, onClick = { selectedTariff = tariff })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                enabled = !loading && !starting && selectedTariff != null,
                onClick = {
                    val tariffId = selectedTariff!!.optString("id")
                    starting = true
                    error = ""
                    Thread {
                        try {
                            val trip = SupabaseClient.createQuickTrip(session, tariffId, customerName)
                            runOnUiThread { onStarted(trip.optJSONObject("trip") ?: trip) }
                        } catch (e: Exception) {
                            runOnUiThread { error = e.message ?: "Unable to start meter"; starting = false }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (starting) "Starting…" else "Start Meter") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        }
    }

    @Composable
    private fun InvoiceScreen(invoice: JSONObject, onDone: () -> Unit) {
        val breakdown = invoice.optJSONObject("breakdown") ?: JSONObject()
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("TRIP COMPLETED", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text("SBS Travels", style = MaterialTheme.typography.titleLarge)
            Text("Invoice " + invoice.optString("invoice_number", "—"))
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fare Summary", style = MaterialTheme.typography.titleMedium)
                    FareRow("Base fare", breakdown.optDouble("base_fare", 0.0))
                    FareRow("Distance (" + String.format("%.2f", breakdown.optDouble("distance_km", 0.0)) + " km)", breakdown.optDouble("distance_fare", 0.0))
                    FareRow("Waiting (" + String.format("%.1f", breakdown.optDouble("waiting_minutes", 0.0)) + " min)", breakdown.optDouble("waiting_minutes", 0.0) * breakdown.optDouble("waiting_per_minute", 0.0))
                    FareRow("Extras", breakdown.optDouble("extra_fare", 0.0))
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("TOTAL", style = MaterialTheme.typography.titleLarge)
                        Text("₹" + String.format("%.2f", invoice.optDouble("total", 0.0)), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                try {
                    val file = InvoicePdf.create(this@MainActivity, invoice)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share Invoice"))
                } catch (_: Exception) {}
            }, modifier = Modifier.fillMaxWidth()) { Text("Share PDF Invoice") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Back to Trips") }
        }
    }

    @Composable
    private fun FareRow(label: String, amount: Double) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("₹" + String.format("%.2f", amount))
        }
    }

    companion object { var currentSession: Session? = null }
}