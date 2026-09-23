import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization,apikey,content-type"}});
Deno.serve(async(req)=>{
 if(req.method==="OPTIONS")return json({});
 const h=req.headers.get("Authorization");if(!h?.startsWith("Bearer "))return json({error:"Authentication required"},401);
 const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{autoRefreshToken:false,persistSession:false}});
 const {data:u,error:ue}=await admin.auth.getUser(h.slice(7));if(ue||!u.user)return json({error:"Invalid authentication token"},401);
 const {data:p}=await admin.from("profiles").select("id,organization_id,full_name,phone,role,is_active").eq("id",u.user.id).maybeSingle();
 if(!p?.is_active||!["ADMIN","DISPATCHER"].includes(p.role))return json({error:"Dispatcher or admin access required"},403);
 const {data:drivers,error:de}=await admin.from("drivers").select("id,driver_code,status,activation_required,profiles!inner(full_name,phone)").eq("organization_id",p.organization_id).order("driver_code");
 if(de)return json({error:de.message},500);
 const {data:tariffs,error:te}=await admin.from("tariffs").select("id,name,mode,version,rules,is_active,effective_from,effective_until").eq("organization_id",p.organization_id).order("name");
 if(te)return json({error:te.message},500);
 return json({profile:p,drivers:drivers??[],tariffs:tariffs??[]});
});