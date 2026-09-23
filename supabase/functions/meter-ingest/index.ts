import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const h = req.headers.get("Authorization");
  if (!h?.startsWith("Bearer ")) return json({ error: "Authentication required" }, 401);

  const admin = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { autoRefreshToken: false, persistSession: false } },
  );

  const { data: u, error: ue } = await admin.auth.getUser(h.slice(7));
  if (ue || !u.user) return json({ error: "Invalid authentication token" }, 401);

  let b: any;
  try { b = await req.json(); } catch { return json({ error: "Invalid JSON body" }, 400); }

  if (!b.trip_id || !Array.isArray(b.events) || b.events.length < 1 || b.events.length > 500) {
    return json({ error: "trip_id and 1-500 events are required" }, 400);
  }

  const { data: p } = await admin
    .from("profiles")
    .select("id,organization_id,role,is_active")
    .eq("id", u.user.id)
    .maybeSingle();

  if (!p?.is_active || p.role !== "DRIVER") {
    return json({ error: "Only an active driver may ingest meter events" }, 403);
  }

  const { data: d } = await admin
    .from("drivers")
    .select("id")
    .eq("profile_id", p.id)
    .eq("organization_id", p.organization_id)
    .maybeSingle();

  if (!d) return json({ error: "Driver record not found" }, 403);

  const { data: t } = await admin
    .from("trips")
    .select("id,driver_id,organization_id,status")
    .eq("id", b.trip_id)
    .eq("organization_id", p.organization_id)
    .maybeSingle();

  if (!t || t.driver_id !== d.id) return json({ error: "Trip not assigned to this driver" }, 403);
  if (!["STARTED", "RUNNING", "WAITING"].includes(t.status)) {
    return json({ error: "Trip is not accepting meter events in its current state" }, 409);
  }

  const rows = b.events.map((e: any) => ({
    trip_id: t.id,
    organization_id: t.organization_id,
    sequence_no: e.sequence_no,
    captured_at: e.captured_at,
    latitude: e.latitude ?? null,
    longitude: e.longitude ?? null,
    accuracy_m: e.accuracy_m ?? null,
    speed_mps: e.speed_mps ?? null,
    distance_delta_m: e.distance_delta_m ?? 0,
    waiting_delta_seconds: e.waiting_delta_seconds ?? 0,
    fare_snapshot: e.fare_snapshot ?? null,
    client_event_id: e.client_event_id,
  }));

  const seen = new Set<number>();
  for (const r of rows) {
    if (!r.client_event_id || !Number.isInteger(r.sequence_no) || r.sequence_no < 0 || !r.captured_at) {
      return json({ error: "Each event needs client_event_id, non-negative integer sequence_no and captured_at" }, 400);
    }
    if (seen.has(r.sequence_no)) {
      return json({ error: "Duplicate meter sequence in batch" }, 409);
    }
    seen.add(r.sequence_no);
  }

  // Meter sequence is the idempotency key for a trip. This also lets a driver
  // safely retry a batch after a network timeout without failing on an already
  // stored event that has a different client UUID.
  const { data: inserted, error: ie } = await admin
    .from("meter_events")
    .upsert(rows, { onConflict: "trip_id,sequence_no", ignoreDuplicates: true })
    .select("id,client_event_id,sequence_no");

  if (ie) return json({ error: "Meter event ingestion failed", details: ie.message }, 500);

  return json({ accepted: inserted?.length ?? 0 });
});
