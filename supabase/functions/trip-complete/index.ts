import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"Content-Type":"application/json","Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization,apikey,content-type"}});
const n=(o:any,k:string,d=0)=>Number.isFinite(Number(o?.[k]))?Number(o[k]):d;
Deno.serve(async(req)=>{
 if(req.method==="OPTIONS")return json({});
 if(req.method!=="POST")return json({error:"POST required"},405);
 const h=req.headers.get("Authorization");if(!h?.startsWith("Bearer "))return json({error:"Authentication required"},401);
 const db=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{auth:{autoRefreshToken:false,persistSession:false}});
 const {data:u,error:ue}=await db.auth.getUser(h.slice(7));if(ue||!u.user)return json({error:"Invalid authentication token"},401);
 const {data:p}=await db.from("profiles").select("id,organization_id,role,is_active").eq("id",u.user.id).maybeSingle();
 if(!p?.is_active||p.role!=="DRIVER")return json({error:"Active driver access required"},403);
 const b=await req.json().catch(()=>({}));const tripId=String(b.trip_id??"");
 if(!tripId)return json({error:"trip_id required"},400);
 const {data:driver}=await db.from("drivers").select("id").eq("profile_id",p.id).eq("organization_id",p.organization_id).maybeSingle();
 if(!driver)return json({error:"Driver record not found"},404);
 const {data:trip}=await db.from("trips").select("*").eq("id",tripId).eq("organization_id",p.organization_id).eq("driver_id",driver.id).maybeSingle();
 if(!trip)return json({error:"Trip not found"},404);
 if(trip.status==="COMPLETED"){
   const {data:existing}=await db.from("invoices").select("*").eq("trip_id",tripId).maybeSingle();
   if(existing)return json({trip_id:tripId,total_fare:trip.total_fare,distance_km:trip.distance_km,waiting_minutes:trip.waiting_minutes,invoice:existing,idempotent:true});
   return json({error:"Trip is completed but invoice is missing; manual reconciliation required"},409);
 }
 if(!["RUNNING","WAITING"].includes(trip.status))return json({error:"Trip must be RUNNING or WAITING"},400);
 if(!trip.started_at)return json({error:"Trip has no start time"},409);
 const {data:events,error:ee}=await db.from("meter_events").select("distance_delta_m,waiting_delta_seconds,sequence_no").eq("trip_id",tripId).eq("organization_id",p.organization_id).order("sequence_no",{ascending:true});
 if(ee)return json({error:ee.message},500);
 const rows=events??[];
 const seen=new Set<number>(); let prev=0;
 for(const e of rows){
   const seq=n(e,"sequence_no",-1),distance=n(e,"distance_delta_m",-1),waiting=n(e,"waiting_delta_seconds",-1);
   if(!Number.isInteger(seq)||seq<1)return json({error:"Invalid meter sequence"},409);
   if(seen.has(seq))return json({error:"Duplicate meter sequence detected; trip cannot be completed safely"},409);
   if(seq<prev)return json({error:"Meter sequence is out of order"},409);
   if(distance<0||waiting<0)return json({error:"Negative meter event values are invalid"},409);
   seen.add(seq);prev=seq;
 }
 const distanceKm=rows.reduce((s,e)=>s+n(e,"distance_delta_m",0),0)/1000;
 const waitingMinutes=rows.reduce((s,e)=>s+n(e,"waiting_delta_seconds",0),0)/60;
 const snap=trip.tariff_snapshot??{};
 const mode=String(snap.mode??"METER");
 const r=snap.rules??{};
 let baseFare=0,distanceFare=0,waitingFare=0,extraFare=0,calculation:any={};
 if(mode==="METER"){
   baseFare=n(r,"base_fare",75);distanceFare=distanceKm*n(r,"per_km",28);waitingFare=waitingMinutes*n(r,"waiting_per_minute",2);
   calculation={mode,base_fare:baseFare,distance_km:distanceKm,per_km:n(r,"per_km",28),waiting_minutes:waitingMinutes,waiting_per_minute:n(r,"waiting_per_minute",2)};
 }else if(mode==="HOURLY"){
   const started=new Date(trip.started_at??trip.created_at).getTime(),hours=Math.max(0,(Date.now()-started)/3600000);
   const rate=n(r,"hourly_rate",350),free=n(r,"free_km_per_hour",10),excess=n(r,"excess_km_rate",20);
   baseFare=hours*rate;const included=hours*free;distanceFare=Math.max(0,distanceKm-included)*excess;
   calculation={mode,hours,hourly_rate:rate,included_km:included,distance_km:distanceKm,excess_km:Math.max(0,distanceKm-included),excess_km_rate:excess};
 }else if(mode==="AIRPORT"){
   baseFare=n(r,"base_fare",0);distanceFare=n(r,"fixed_fare",n(r,"airport_fare",0));
   if(distanceFare===0)distanceFare=distanceKm*n(r,"per_km",0);
   extraFare=n(r,"toll",0)+n(r,"parking",0)+n(r,"permit",0)+n(r,"extra_fare",0);
   calculation={mode,fixed_fare:distanceFare,toll:n(r,"toll",0),parking:n(r,"parking",0),permit:n(r,"permit",0)};
 }else if(mode==="OUTSTATION_ONE_WAY"||mode==="OUTSTATION_ROUND_TRIP"){
   const minKm=n(r,"minimum_km",n(r,"min_km",130)),rate=n(r,"rate_per_km",n(r,"per_km",16));
   const billedKm=Math.max(distanceKm,minKm);
   distanceFare=billedKm*rate;
   if(mode==="OUTSTATION_ROUND_TRIP") {
     const days=Math.max(1,n(r,"days",1)),roundKm=n(r,"round_trip_km",minKm);
     distanceFare=Math.max(distanceFare,roundKm*rate);
     extraFare=n(r,"driver_allowance_per_day",0)*days+n(r,"night_halt_per_day",0)*Math.max(0,days-1);
   }
   extraFare+=n(r,"toll",0)+n(r,"parking",0)+n(r,"permit",0)+n(r,"extra_fare",0);
   calculation={mode,distance_km:distanceKm,billed_km:billedKm,rate_per_km:rate,minimum_km:minKm,extra_fare:extraFare};
 }else return json({error:"Unsupported tariff mode: "+mode},400);
 const total=baseFare+distanceFare+waitingFare+extraFare;
 if(!Number.isFinite(total)||total<0)return json({error:"Calculated fare is invalid"},409);
 const invoiceNumber="SBS-"+new Date().toISOString().slice(0,10).replaceAll("-","")+"-"+tripId.replaceAll("-","").slice(0,8).toUpperCase();
 const {error:te}=await db.from("trips").update({status:"COMPLETED",completed_at:new Date().toISOString(),distance_km:distanceKm,waiting_minutes:waitingMinutes,base_fare:baseFare,distance_fare:distanceFare,waiting_fare:waitingFare,extra_fare:extraFare,total_fare:total}).eq("id",tripId).eq("status",trip.status);
 if(te)return json({error:te.message},500);
 const {data:invoice,error:ie}=await db.from("invoices").upsert({trip_id:tripId,organization_id:p.organization_id,invoice_number:invoiceNumber,subtotal:baseFare+distanceFare,extra_total:waitingFare+extraFare,total,breakdown:{...calculation,base_fare:baseFare,distance_fare:distanceFare,waiting_fare:waitingFare,extra_fare:extraFare,total},issued_at:new Date().toISOString()},{onConflict:"trip_id"}).select("*").single();
 if(ie)return json({error:ie.message,trip_completed:true},500);
 if(te)return json({error:te.message},500);
 await db.from("trip_events").insert({trip_id:tripId,organization_id:p.organization_id,event_type:"TRIP_COMPLETED",from_status:trip.status,to_status:"COMPLETED",actor_profile_id:p.id,payload:{distance_km:distanceKm,waiting_minutes:waitingMinutes,total_fare:total,tariff_mode:mode}});
 return json({trip_id:tripId,total_fare:total,distance_km:distanceKm,waiting_minutes:waitingMinutes,invoice});
});