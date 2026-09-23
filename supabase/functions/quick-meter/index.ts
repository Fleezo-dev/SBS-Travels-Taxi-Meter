import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{
  "Content-Type":"application/json","Access-Control-Allow-Origin":"*",
  "Access-Control-Allow-Headers":"authorization,apikey,content-type",
  "Access-Control-Allow-Methods":"GET,POST,OPTIONS"
}});

Deno.serve(async(req)=>{
  if(req.method==="OPTIONS") return json({});
  const auth=req.headers.get("Authorization");
  if(!auth?.startsWith("Bearer ")) return json({error:"Authentication required"},401);

  const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,{
    auth:{autoRefreshToken:false,persistSession:false}
  });
  const {data:u,error:ue}=await admin.auth.getUser(auth.slice(7));
  if(ue||!u.user) return json({error:"Invalid authentication token"},401);

  const {data:p}=await admin.from("profiles")
    .select("id,organization_id,role,is_active")
    .eq("id",u.user.id).maybeSingle();

  if(!p?.is_active || p.role!=="DRIVER") return json({error:"Active driver access required"},403);

  const {data:d}=await admin.from("drivers")
    .select("id,status,organization_id").eq("profile_id",p.id)
    .eq("organization_id",p.organization_id).maybeSingle();
  if(!d || d.status!=="ACTIVE") return json({error:"Active driver required"},403);

  const now=new Date();

  if(req.method==="GET"){
    const {data:tariffs,error}=await admin.from("tariffs")
      .select("id,name,mode,version,rules,effective_from,effective_until")
      .eq("organization_id",p.organization_id)
      .eq("is_active",true)
      .in("mode",["METER","HOURLY"])
      .order("name");
    if(error) return json({error:error.message},400);
    const active=(tariffs??[]).filter((t:any)=>
      (!t.effective_from || new Date(t.effective_from)<=now) &&
      (!t.effective_until || new Date(t.effective_until)>=now)
    );
    return json({tariffs:active});
  }

  if(req.method!=="POST") return json({error:"Method not allowed"},405);
  const b=await req.json().catch(()=>({}));
  if(!b.tariff_id) return json({error:"tariff_id is required"},400);

  const {data:t,error:te}=await admin.from("tariffs")
    .select("id,name,mode,version,rules,is_active,effective_from,effective_until")
    .eq("id",b.tariff_id).eq("organization_id",p.organization_id).maybeSingle();
  if(te||!t||!t.is_active) return json({error:"Active tariff required"},400);
  if(t.effective_from&&new Date(t.effective_from)>now) return json({error:"Tariff is not effective yet"},400);
  if(t.effective_until&&new Date(t.effective_until)<now) return json({error:"Tariff has expired"},400);
  if(!["METER","HOURLY"].includes(t.mode)) return json({error:"This tariff is not supported by Quick Meter yet"},400);

  const startedAt=now.toISOString();
  const row={
    organization_id:p.organization_id,driver_id:d.id,assigned_by:null,status:"STARTED",
    customer_name:b.customer_name??null,customer_phone:b.customer_phone??null,
    pickup_address:b.pickup_address??null,pickup_lat:b.pickup_lat??null,pickup_lng:b.pickup_lng??null,
    destination_address:b.destination_address??null,destination_lat:b.destination_lat??null,destination_lng:b.destination_lng??null,
    tariff_id:t.id,tariff_snapshot:{id:t.id,name:t.name,mode:t.mode,version:t.version,rules:t.rules},
    started_at:startedAt,notes:b.notes??"QUICK_METER"
  };
  const {data:trip,error:tr}=await admin.from("trips").insert(row)
    .select("id,status,driver_id,customer_name,customer_phone,pickup_address,destination_address,tariff_id,tariff_snapshot,started_at,created_at")
    .single();
  if(tr) return json({error:tr.message},400);

  await admin.from("trip_events").insert({
    trip_id:trip.id,organization_id:p.organization_id,event_type:"QUICK_TRIP_STARTED",
    from_status:null,to_status:"STARTED",actor_profile_id:p.id,
    payload:{tariff_id:t.id,mode:t.mode}
  });
  await admin.from("audit_events").insert({
    organization_id:p.organization_id,actor_profile_id:p.id,event_type:"QUICK_TRIP_STARTED",
    entity_type:"trip",entity_id:trip.id,payload:{driver_id:d.id,tariff_id:t.id}
  });

  return json({trip},201);
});
