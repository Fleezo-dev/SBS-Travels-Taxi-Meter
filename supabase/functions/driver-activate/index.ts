import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

type ActivationRequest = {
  driver_code?: string;
  device_fingerprint?: string;
  device_name?: string;
  app_version?: string;
};

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return new Response(JSON.stringify({ error: "Authentication required" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  const jwt = authHeader.slice("Bearer ".length);
  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const { data: userData, error: userError } = await admin.auth.getUser(jwt);
  if (userError || !userData.user) {
    return new Response(JSON.stringify({ error: "Invalid authentication token" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  let body: ActivationRequest;
  try {
    body = await req.json();
  } catch {
    return new Response(JSON.stringify({ error: "Invalid JSON body" }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });
  }

  const driverCode = body.driver_code?.trim();
  const fingerprint = body.device_fingerprint?.trim();

  if (!driverCode || !fingerprint) {
    return new Response(JSON.stringify({
      error: "driver_code and device_fingerprint are required",
    }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });
  }

  const { data: profile, error: profileError } = await admin
    .from("profiles")
    .select("id, organization_id, role, is_active")
    .eq("id", userData.user.id)
    .maybeSingle();

  if (profileError || !profile) {
    return new Response(JSON.stringify({ error: "Driver profile not found" }), {
      status: 403,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (profile.role !== "DRIVER" || !profile.is_active) {
    return new Response(JSON.stringify({ error: "Account is not an active driver" }), {
      status: 403,
      headers: { "Content-Type": "application/json" },
    });
  }

  const { data: driver, error: driverError } = await admin
    .from("drivers")
    .select("id, profile_id, organization_id, driver_code, status, activation_required")
    .eq("organization_id", profile.organization_id)
    .eq("profile_id", profile.id)
    .eq("driver_code", driverCode)
    .maybeSingle();

  if (driverError || !driver) {
    return new Response(JSON.stringify({ error: "Driver activation code not found" }), {
      status: 403,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (driver.status !== "ACTIVE") {
    return new Response(JSON.stringify({ error: "Driver is not activated by the administrator" }), {
      status: 403,
      headers: { "Content-Type": "application/json" },
    });
  }

  const now = new Date().toISOString();

  const { data: existingDevice } = await admin
    .from("devices")
    .select("id, driver_id, status, device_fingerprint")
    .eq("organization_id", driver.organization_id)
    .eq("device_fingerprint", fingerprint)
    .maybeSingle();

  if (existingDevice && existingDevice.driver_id !== driver.id) {
    return new Response(JSON.stringify({ error: "This device is already bound to another driver" }), {
      status: 409,
      headers: { "Content-Type": "application/json" },
    });
  }

  let deviceId: string;

  if (existingDevice) {
    const { error } = await admin
      .from("devices")
      .update({
        device_name: body.device_name ?? null,
        app_version: body.app_version ?? null,
        status: "ACTIVE",
        activated_at: now,
        revoked_at: null,
        last_seen_at: now,
      })
      .eq("id", existingDevice.id);

    if (error) throw error;
    deviceId = existingDevice.id;
  } else {
    const { data: device, error } = await admin
      .from("devices")
      .insert({
        driver_id: driver.id,
        organization_id: driver.organization_id,
        device_fingerprint: fingerprint,
        device_name: body.device_name ?? null,
        app_version: body.app_version ?? null,
        status: "ACTIVE",
        activated_at: now,
        last_seen_at: now,
      })
      .select("id")
      .single();

    if (error || !device) throw error ?? new Error("Device creation failed");
    deviceId = device.id;
  }

  await admin
    .from("drivers")
    .update({ activated_at: driver.activation_required ? now : undefined, updated_at: now })
    .eq("id", driver.id);

  await admin.from("audit_events").insert({
    organization_id: driver.organization_id,
    actor_profile_id: profile.id,
    event_type: "DRIVER_DEVICE_ACTIVATED",
    entity_type: "device",
    entity_id: deviceId,
    payload: { driver_id: driver.id, device_id: deviceId, app_version: body.app_version ?? null },
  });

  return new Response(JSON.stringify({
    activated: true,
    driver_id: driver.id,
    device_id: deviceId,
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
});
