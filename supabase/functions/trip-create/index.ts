import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization,apikey,content-type","Access-Control-Allow-Methods":"POST,OPTIONS"}});
async function sha256Hex(value:string){const b=new TextEncoder().encode(value);const h=await crypto.subtle.digest("SHA-256",b);return [...new Uint8Array(h)].map(x=>x.toString(16).padStart(2,"0")).join("");}
function otp(){const a=new Uint32Array(1);crypto.getRandomValues(a);return String(a[0]%1000000).padStart(6,"0");}
Deno.serve(async(req)=>{
 if(req.method==="OPTIONS")return json({});
 if(req.method!=="POST")return json({error:"Method not allowed"},405);
 const auth=req.headers.get("Authorization");if(!auth?.startsWith("Bearer "))return json({error:"Authentication required"},401);
 const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{autoRefreshToken:false,persistSession:false}});
 const {data:u,error:ue}=await admin.auth.getUser(auth.slice(7));if(ue||!u.user)return json({error:"Invalid authentication token"},401);
 const {data:p}=await admin.from("profiles").select("id,organization_id,role,is_active").eq("id",u.user.id).maybeSingle();
 if(!p?.is_active||!["ADMIN","DISPATCHER"].includes(p.role))return json({error:"Dispatcher or admin access required"},403);
 const b=await req.json().catch(()=>({}));
 if(!b.driver_id||!b.tariff_id)return json({error:"driver_id and tariff_id are required"},400);
 const {data:d}=await admin.from("drivers").select("id,status,organization_id").eq("id",b.driver_id).eq("organization_id",p.organization_id).maybeSingle();
 if(!d||d.status!=="ACTIVE")return json({error:"Active driver required"},400);
 const {data:t}=await admin.from("tariffs").select("id,name,mode,version,rules,is_active,effective_from,effective_until").eq("id",b.tariff_id).eq("organization_id",p.organization_id).maybeSingle();
 if(!t||!t.is_active)return json({error:"Active tariff required"},400);
 const now=new Date();
 if(t.effective_from&&new Date(t.effective_from)>now)return json({error:"Tariff is not effective yet"},400);
 if(t.effective_until&&new Date(t.effective_until)<now)return json({error:"Tariff has expired"},400);
 const code=otp(),hash=await sha256Hex(code);
 const row={organization_id:p.organization_id,driver_id:d.id,assigned_by:p.id,status:"ASSIGNED",customer_name:b.customer_name??null,customer_phone:b.customer_phone??null,pickup_address:b.pickup_address??null,pickup_lat:b.pickup_lat??null,pickup_lng:b.pickup_lng??null,destination_address:b.destination_address??null,destination_lat:b.destination_lat??null,destination_lng:b.destination_lng??null,tariff_id:t.id,tariff_snapshot:{id:t.id,name:t.name,mode:t.mode,version:t.version,rules:t.rules},start_otp_hash:hash,scheduled_at:b.scheduled_at??null,notes:b.notes??null};
 const {data:trip,error:te}=await admin.from("trips").insert(row).select("id,status,driver_id,customer_name,pickup_address,destination_address,tariff_id,tariff_snapshot,scheduled_at,created_at").single();
 if(te)return json({error:te.message},400);
 await admin.from("trip_events").insert({trip_id:trip.id,organization_id:p.organization_id,event_type:"TRIP_CREATED",from_status:null,to_status:"ASSIGNED",actor_profile_id:p.id,payload:{otp_issued:true}});
 await admin.from("audit_events").insert({organization_id:p.organization_id,actor_profile_id:p.id,event_type:"TRIP_CREATED",entity_type:"trip",entity_id:trip.id,payload:{driver_id:d.id,tariff_id:t.id}});
 return json({trip,start_otp:code},201);
});