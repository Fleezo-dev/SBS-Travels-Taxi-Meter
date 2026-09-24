package com.sbstravels.taximeter.data
import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class Session(val accessToken:String,val refreshToken:String?,val userId:String)
object SupabaseClient {
 private const val URL="https://utbbydykhlbgbabbwysz.supabase.co"
 private const val KEY="sb_publishable_WG2E2M7Y6HMks74ms77irQ_VSGaWDkp"
 private const val PREFS="auth_secure"
 private const val OLD_PREFS="auth"
 private const val KEY_ALIAS="sbs_travels_session_key"
 private val http=OkHttpClient()

 private fun key():SecretKey{
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (ks.getKey(KEY_ALIAS,null) as? SecretKey)?.let{return it}
  val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
  gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  return gen.generateKey()
 }
 private fun enc(value:String):String{
  val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
  return Base64.encodeToString(c.iv,Base64.NO_WRAP)+"."+Base64.encodeToString(c.doFinal(value.toByteArray(StandardCharsets.UTF_8)),Base64.NO_WRAP)
 }
 private fun dec(value:String?):String? {
  if(value==null)return null
  return try {
   val x=value.split(".");if(x.size!=2)return null
   val c=Cipher.getInstance("AES/GCM/NoPadding")
   c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(x[0],Base64.NO_WRAP)))
   String(c.doFinal(Base64.decode(x[1],Base64.NO_WRAP)),StandardCharsets.UTF_8)
  } catch(_:Exception) { null }
 }
 private val jsonType="application/json".toMediaType()
 fun deviceFingerprint(context:Context)=Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)?:"unknown-device"
 fun saveSession(context:Context,s:Session){
 context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
  .putString("access",enc(s.accessToken)).putString("refresh",s.refreshToken?.let(::enc)).putString("user",enc(s.userId)).apply()
 context.deleteSharedPreferences(OLD_PREFS)
}
 fun loadSession(context:Context):Session?{
 val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
 val a=dec(p.getString("access",null))
 if(a!=null)return Session(a,dec(p.getString("refresh",null)),dec(p.getString("user",null))?:"")
 val old=context.getSharedPreferences(OLD_PREFS,Context.MODE_PRIVATE)
 val legacy=old.getString("access",null)?:return null
 val s=Session(legacy,old.getString("refresh",null),old.getString("user","")?:"")
 saveSession(context,s);return s
}
 fun clearSession(context:Context){ context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();context.deleteSharedPreferences(OLD_PREFS) }
 fun login(email:String,password:String):Session{
  val body=JSONObject().put("email",email).put("password",password).toString()
  val req=Request.Builder().url(URL+"/auth/v1/token?grant_type=password").addHeader("apikey",KEY).post(body.toRequestBody(jsonType)).build()
  http.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(JSONObject(t).optString("msg",JSONObject(t).optString("error_description","Login failed")));val o=JSONObject(t);return Session(o.getString("access_token"),o.optString("refresh_token",null),o.getJSONObject("user").getString("id"))}
 }
 fun refresh(s:Session):Session{
  val rt=s.refreshToken?:throw IllegalStateException("Session expired; please sign in again")
  val req=Request.Builder().url(URL+"/auth/v1/token?grant_type=refresh_token").addHeader("apikey",KEY)
   .post(JSONObject().put("refresh_token",rt).toString().toRequestBody(jsonType)).build()
  http.newCall(req).execute().use{r->
   val t=r.body?.string().orEmpty()
   if(!r.isSuccessful) throw IllegalStateException("Session expired; please sign in again")
   val o=JSONObject(t)
   return Session(o.getString("access_token"),o.optString("refresh_token",rt),o.getJSONObject("user").getString("id"))
  }
 }
 fun activate(s:Session,c:Context,code:String):String{
  val body=JSONObject().put("driver_code",code).put("device_fingerprint",deviceFingerprint(c)).put("device_name","Android Driver").put("app_version","0.2.0").toString()
  return post(URL+"/functions/v1/driver-activate",s,body).optString("device_id")
 }
 fun driverSession(s:Session)=get(URL+"/functions/v1/driver-session",s)
 fun trips(s:Session)=get(URL+"/functions/v1/driver-trips",s)
 fun loadTrip(s:Session,loadOtp:String)=post(URL+"/functions/v1/trip-load",s,JSONObject().put("load_otp",loadOtp).toString())
 fun quickMeterTariffs(s:Session)=get(URL+"/functions/v1/quick-meter",s)
 fun createQuickTrip(s:Session,tariffId:String,customerName:String?=null)=post(URL+"/functions/v1/quick-meter",s,JSONObject().put("tariff_id",tariffId).apply{if(!customerName.isNullOrBlank())put("customer_name",customerName)}.toString())
 fun history(s:Session)=get(URL+"/functions/v1/driver-history",s)
 fun transition(s:Session,tripId:String,toStatus:String,startOtp:String?=null)=post(URL+"/functions/v1/trip-transition",s,JSONObject().apply{put("trip_id",tripId);put("to_status",toStatus);if(!startOtp.isNullOrBlank())put("start_otp",startOtp)}.toString())
 fun ingestMeterEvents(s:Session,tripId:String,events:org.json.JSONArray):Int{
  if(events.length()==0)return 0
  var acceptedTotal=0
  var offset=0
  while(offset<events.length()){
   val batch=org.json.JSONArray()
   val end=minOf(offset+500,events.length())
   for(i in offset until end)batch.put(events.getJSONObject(i))
   val body=org.json.JSONObject().put("trip_id",tripId).put("events",batch).toString()
   acceptedTotal+=post(URL+"/functions/v1/meter-ingest",s,body).optInt("accepted",0)
   offset=end
  }
  return acceptedTotal
 }
 fun completeTrip(s:Session,tripId:String,extraFare:Double=0.0)=post(URL+"/functions/v1/trip-complete",s,JSONObject().put("trip_id",tripId).put("extra_fare",extraFare).toString())
 private fun get(url:String,s:Session):JSONObject{
  val req=Request.Builder().url(url).addHeader("apikey",KEY).addHeader("Authorization","Bearer "+s.accessToken).get().build()
  http.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(JSONObject(t).optString("error","Request failed"));return JSONObject(t)}
 }
 private fun post(url:String,s:Session,body:String):JSONObject{
  val req=Request.Builder().url(url).addHeader("apikey",KEY).addHeader("Authorization","Bearer "+s.accessToken).post(body.toRequestBody(jsonType)).build()
  http.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(!r.isSuccessful)throw IllegalStateException(JSONObject(t).optString("error","Request failed"));return JSONObject(t)}
 }
}