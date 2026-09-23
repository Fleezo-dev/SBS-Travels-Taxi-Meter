package com.sbstravels.taximeter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sbstravels.taximeter.data.Session
import com.sbstravels.taximeter.data.SupabaseClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { MaterialTheme { TaxiApp() } }
 }
 @Composable private fun TaxiApp() {
  var session by remember { mutableStateOf<Session?>(null) }
  var activated by remember { mutableStateOf(false) }
  var trips by remember { mutableStateOf(JSONArray()) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf("") }

  fun work(block:()->Unit) {
   busy=true; message=""
   Thread { try { block() } catch(e:Exception) { runOnUiThread { message=e.message ?: "Operation failed" } } finally { runOnUiThread { busy=false } } }.start()
  }
  when {
   session == null -> LoginScreen(busy,message) { email,pw ->
    work { val s=SupabaseClient.login(email,pw); runOnUiThread { session=s; currentSession=s } }
   }
   !activated -> ActivationScreen(busy,message) { code ->
    val s=session!!
    work { SupabaseClient.activate(s,this@MainActivity,code); runOnUiThread { activated=true } }
   }
   else -> {
    LaunchedEffect(Unit) { work { val o=SupabaseClient.trips(session!!); runOnUiThread { trips=o.optJSONArray("trips") ?: JSONArray() } } }
    Dashboard(trips,busy,message) {
     work { val o=SupabaseClient.trips(session!!); runOnUiThread { trips=o.optJSONArray("trips") ?: JSONArray() } }
    }
   }
  }
 }

 @Composable private fun LoginScreen(busy:Boolean,message:String,onLogin:(String,String)->Unit) {
  var email by remember{mutableStateOf("")}; var pw by remember{mutableStateOf("")}
  Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center) {
   Text("SBS Travels",style=MaterialTheme.typography.headlineMedium)
   Text("Taxi Meter • Driver App",style=MaterialTheme.typography.titleMedium)
   Spacer(Modifier.height(24.dp))
   OutlinedTextField(email,{email=it},label={Text("Driver email")},modifier=Modifier.fillMaxWidth())
   Spacer(Modifier.height(10.dp))
   OutlinedTextField(pw,{pw=it},label={Text("Password")},modifier=Modifier.fillMaxWidth())
   Spacer(Modifier.height(16.dp))
   Button({onLogin(email,pw)},enabled=!busy&&email.isNotBlank()&&pw.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(busy)"Signing in…" else "Sign in") }
   if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp))
  }
 }

 @Composable private fun ActivationScreen(busy:Boolean,message:String,onActivate:(String)->Unit) {
  var code by remember{mutableStateOf("")}
  Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center) {
   Text("Driver Activation",style=MaterialTheme.typography.headlineSmall)
   Spacer(Modifier.height(8.dp)); Text("Activation is mandatory. This device must be approved before trips or the meter can be used.")
   Spacer(Modifier.height(20.dp))
   OutlinedTextField(code,{code=it},label={Text("Driver activation code")},modifier=Modifier.fillMaxWidth())
   Spacer(Modifier.height(14.dp))
   Button({onActivate(code)},enabled=!busy&&code.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(busy)"Activating…" else "Activate Device") }
   if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp))
  }
 }

 @Composable private fun Dashboard(trips:JSONArray,busy:Boolean,message:String,onRefresh:()->Unit) {
  Column(Modifier.fillMaxSize().padding(16.dp)) {
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
    Text("Assigned Trips",style=MaterialTheme.typography.headlineSmall)
    TextButton(onClick=onRefresh,enabled=!busy){Text("Refresh")}
   }
   if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
   if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.error)
   if(trips.length()==0&&!busy) Text("No active trips",modifier=Modifier.padding(top=30.dp))
   LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxSize().padding(top=12.dp)) {
    items((0 until trips.length()).toList()) { TripCard(trips.getJSONObject(it),onRefresh) }
   }
  }
 }

 @Composable private fun TripCard(t:JSONObject,onRefresh:()->Unit) {
  var busy by remember{mutableStateOf(false)}; var err by remember{mutableStateOf("")}
  val status=t.optString("status")
  val next=when(status) {
   "ASSIGNED"->"ACCEPTED"; "ACCEPTED"->"STARTED"; "STARTED"->"RUNNING"; "RUNNING"->"COMPLETED"; "WAITING"->"RUNNING"; else->null
  }
  Card(Modifier.fillMaxWidth()) {
   Column(Modifier.padding(14.dp)) {
    Text(t.optString("customer_name","Customer")+" • "+status,style=MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp)); Text(t.optString("pickup_address","Pickup"))
    Text("→ "+t.optString("destination_address","Destination"))
    if(!t.isNull("total_fare")) Text("Current fare ₹"+t.optDouble("total_fare",0.0))
    if(next!=null) {
     Spacer(Modifier.height(8.dp))
     Button(enabled=!busy,onClick={
      busy=true
      Thread { try {
       SupabaseClient.transition(currentSession!!,t.getString("id"),next)
       onRefresh()
      } catch(e:Exception) { err=e.message ?: "Trip update failed" } finally { busy=false } }.start()
     }) { Text(if(busy)"Updating…" else when(next){"ACCEPTED"->"Accept Trip";"STARTED"->"Start Trip";"RUNNING"->"Resume Trip";else->"Complete Trip"}) }
    }
    if(err.isNotBlank()) Text(err,color=MaterialTheme.colorScheme.error)
   }
  }
 }
 companion object { var currentSession:Session?=null }
}