import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization,apikey,content-type","Access-Control-Allow-Methods":"POST,OPTIONS"}});
async function sha256Hex(value:string){const b=new TextEncoder().encode(value);const h=await crypto.subtle.digest("SHA-256",b);return [...new Uint8Array(h)].map(x=>x.toString(16).padStart(2,"0")).join("");}
Deno.serve(async(req)=>{
 if(req.method==="OPTIONS")return json({});
 if(req.method!=="POST")return json({error:"Method not allowed"},405);
 const auth=req.headers.get("Authorization");if(!auth?.startsWith("Bearer "))return json({error:"Authentication required"},401);
 const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{autoRefreshToken:false,persistSession:false}});
 const {data:u,error:ue}=await admin.auth.getUser(auth.slice(7));if(ue||!u.user)return json({error:"Invalid authentication token"},401);
 const {data:p}=await admin.from("profiles").select("id,organization_id,role,is_active").eq("id",u.user.id).maybeSingle();
 if(!p?.is_active||p.role!=="DRIVER")return json({error:"Active driver access required"},403);
 const {data:d}=await admin.from("drivers").select("id,status,organization_id").eq("profile_id",p.id).eq("organization_id",p.organization_id).maybeSingle();
 if(!d||d.status!=="ACTIVE")return json({error:"Active driver required"},403);
 const b=await req.json().catch(()=>({}));
 const code=String(b.load_otp??"").trim();
 if(!/^\d{6}$/.test(code))return json({error:"A 6-digit load OTP is required"},400);
 const hash=await sha256Hex(code);
 const {data:trip}=await admin.from("trips")
   .select("id,status,driver_id,customer_name,customer_phone,pickup_address,pickup_lat,pickup_lng,destination_address,destination_lat,destination_lng,tariff_id,tariff_snapshot,scheduled_at,notes,created_at")
   .eq("organization_id",p.organization_id).eq("driver_id",d.id).eq("status","ASSIGNED")
   .eq("load_otp_hash",hash).is("load_otp_used_at",null).maybeSingle();
 if(!trip)return json({error:"Invalid, expired, already used, or wrong-driver load OTP"},404);
 const now=new Date().toISOString();
 const {data:claimed,error:ce}=await admin.from("trips").update({load_otp_used_at:now}).eq("id",trip.id).eq("driver_id",d.id).eq("status","ASSIGNED").is("load_otp_used_at",null).select("id,status,driver_id,customer_name,customer_phone,pickup_address,pickup_lat,pickup_lng,destination_address,destination_lat,destination_lng,tariff_id,tariff_snapshot,scheduled_at,notes,created_at").maybeSingle();
 if(ce)return json({error:ce.message},400);
 if(!claimed)return json({error:"Trip was already loaded"},409);
 await admin.from("trip_events").insert({trip_id:claimed.id,organization_id:p.organization_id,event_type:"TRIP_LOADED",from_status:"ASSIGNED",to_status:"ASSIGNED",actor_profile_id:p.id,payload:{load_otp_used:true}});
 await admin.from("audit_events").insert({organization_id:p.organization_id,actor_profile_id:p.id,event_type:"TRIP_LOADED",entity_type:"trip",entity_id:claimed.id,payload:{driver_id:d.id}});
 return json({trip:claimed,loaded:true});
});