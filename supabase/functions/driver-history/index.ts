import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json"}});
Deno.serve(async(req)=>{
 if(req.method!=="GET") return json({error:"Method not allowed"},405);
 const h=req.headers.get("Authorization"); if(!h?.startsWith("Bearer ")) return json({error:"Authentication required"},401);
 const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{autoRefreshToken:false,persistSession:false}});
 const {data:u,error:ue}=await admin.auth.getUser(h.slice(7)); if(ue||!u.user) return json({error:"Invalid authentication token"},401);
 const {data:p,error:pe}=await admin.from("profiles").select("id,organization_id,role,is_active,full_name,phone").eq("id",u.user.id).maybeSingle();
 if(pe||!p?.is_active||p.role!=="DRIVER") return json({error:"Active driver access required"},403);
 const {data:d,error:de}=await admin.from("drivers").select("id,driver_code,status,activation_required,activated_at,last_seen_at").eq("profile_id",p.id).eq("organization_id",p.organization_id).maybeSingle();
 if(de||!d) return json({error:"Driver record not found"},404);
 const limit=Math.min(Math.max(Number(new URL(req.url).searchParams.get("limit")||50),1),100);
 const {data:trips,error:te}=await admin.from("trips").select("id,status,customer_name,customer_phone,pickup_address,destination_address,scheduled_at,tariff_id,tariff_snapshot,distance_km,waiting_minutes,base_fare,distance_fare,waiting_fare,extra_fare,total_fare,notes,created_at,accepted_at,started_at,completed_at").eq("organization_id",p.organization_id).eq("driver_id",d.id).in("status",["COMPLETED","CANCELLED"]).order("completed_at",{ascending:false,nullsFirst:false}).limit(limit);
 if(te) return json({error:te.message},500);
 return json({profile:p,driver:d,trips:trips??[]});
});