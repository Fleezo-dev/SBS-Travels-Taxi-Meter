package com.sbstravels.taximeter.data
import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class Session(val accessToken:String,val refreshToken:String?,val userId:String)
object SupabaseClient {
 private const val URL="https://utbbydykhlbgbabbwysz.supabase.co"
 private const val KEY="sb_publishable_WG2E2M7Y6HMks74ms77irQ_VSGaWDkp"
 private val http=OkHttpClient()
 private val jsonType="application/json".toMediaType()
 fun deviceFingerprint(context:Context)=Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)?:"unknown-device"
 fun login(email:String,password:String):Session{
  val body=JSONObject().put("email",email).put("password",password).toString()
  val req=Request.Builder().url(URL+"/auth/v1/token?grant_type=password").addHeader("apikey",KEY).post(body.toRequestBody(jsonType)).build()
  http.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(JSONObject(t).optString("msg",JSONObject(t).optString("error_description","Login failed")));val o=JSONObject(t);return Session(o.getString("access_token"),o.optString("refresh_token",null),o.getJSONObject("user").getString("id"))}
 }
 fun activate(s:Session,c:Context,code:String):String{
  val body=JSONObject().put("driver_code",code).put("device_fingerprint",deviceFingerprint(c)).put("device_name","Android Driver").put("app_version","0.2.0").toString()
  return post(URL+"/functions/v1/driver-activate",s,body).optString("device_id")
 }
 fun driverSession(s:Session)=get(URL+"/functions/v1/driver-session",s)
 fun trips(s:Session)=get(URL+"/functions/v1/driver-trips",s)
 fun transition(s:Session,tripId:String,toStatus:String)=post(URL+"/functions/v1/trip-transition",s,JSONObject().put("trip_id",tripId).put("to_status",toStatus).toString())
 private fun get(url:String,s:Session):JSONObject{
  val req=Request.Builder().url(url).addHeader("apikey",KEY).addHeader("Authorization","Bearer "+s.accessToken).get().build()
  http.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(JSONObject(t).optString("error","Request failed"));return JSONObject(t)}
 }
 private fun post(url:String,s:Session,body:String):JSONObject{
  val req=Request.Builder().url(url).addHeader("apikey",KEY).addHeader("Authorization","Bearer "+s.accessToken).post(body.toRequestBody(jsonType)).build()
  http.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(JSONObject(t).optString("error","Request failed"));return JSONObject(t)}
 }
}