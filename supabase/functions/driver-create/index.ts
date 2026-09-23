import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization,apikey,content-type"}});
function randomPassword(){const chars="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";const bytes=new Uint8Array(12);crypto.getRandomValues(bytes);return Array.from(bytes,b=>chars[b%chars.length]).join("");}
Deno.serve(async(req)=>{
 if(req.method==="OPTIONS")return json({});
 if(req.method!=="POST")return json({error:"POST required"},405);
 const auth=req.headers.get("Authorization");if(!auth?.startsWith("Bearer "))return json({error:"Authentication required"},401);
 const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{autoRefreshToken:false,persistSession:false}});
 const {data:u,error:ue}=await admin.auth.getUser(auth.slice(7));if(ue||!u.user)return json({error:"Invalid authentication token"},401);
 const {data:actor}=await admin.from("profiles").select("id,organization_id,role,is_active").eq("id",u.user.id).maybeSingle();
 if(!actor?.is_active||!["ADMIN","DISPATCHER"].includes(actor.role))return json({error:"Admin or dispatcher access required"},403);
 let b:any;try{b=await req.json()}catch{return json({error:"Invalid JSON"},400)}
 const email=String(b.email??"").trim().toLowerCase(),fullName=String(b.full_name??"").trim(),phone=String(b.phone??"").trim(),driverCode=String(b.driver_code??"").trim().toUpperCase();
 if(!email||!fullName||!driverCode)return json({error:"email, full_name and driver_code are required"},400);
 if(!/^\S+@\S+\.\S+$/.test(email))return json({error:"Enter a valid driver email"},400);
 if(!/^[A-Z0-9_-]{2,30}$/.test(driverCode))return json({error:"Invalid driver code"},400);
 const {data:code}=await admin.from("drivers").select("id").eq("organization_id",actor.organization_id).eq("driver_code",driverCode).maybeSingle();
 if(code)return json({error:"Driver code already exists"},409);
 const {data:users}=await admin.auth.admin.listUsers({page:1,perPage:1000});
 if(users?.users?.some(x=>x.email?.toLowerCase()===email))return json({error:"An account already exists for this email"},409);
 const temporaryPassword=randomPassword();
 const {data:created,error:ce}=await admin.auth.admin.createUser({email,password:temporaryPassword,email_confirm:true,user_metadata:{full_name:fullName,phone}});
 if(ce||!created.user)return json({error:ce?.message??"Unable to create driver account"},400);
 const userId=created.user.id;
 const {error:pe}=await admin.from("profiles").insert({id:userId,organization_id:actor.organization_id,full_name:fullName,phone:phone||null,role:"DRIVER",is_active:true});
 if(pe){await admin.auth.admin.deleteUser(userId);return json({error:pe.message},500);}
 const {data:driver,error:de}=await admin.from("drivers").insert({profile_id:userId,organization_id:actor.organization_id,driver_code:driverCode,status:"ACTIVE",activation_required:true}).select("id,profile_id,driver_code,status,activation_required").single();
 if(de||!driver){await admin.from("profiles").delete().eq("id",userId);await admin.auth.admin.deleteUser(userId);return json({error:de?.message??"Unable to create driver record"},500);}
 await admin.from("audit_events").insert({organization_id:actor.organization_id,actor_profile_id:actor.id,event_type:"DRIVER_CREATED",entity_type:"driver",entity_id:driver.id,payload:{email,full_name:fullName,phone:phone||null,driver_code:driverCode}});
 return json({driver,login_email:email,temporary_password:temporaryPassword,activation_required:true},201);
});