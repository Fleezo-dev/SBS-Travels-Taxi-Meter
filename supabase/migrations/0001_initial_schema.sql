-- SBS Travels initial backend schema
-- This migration is intentionally prepared in GitHub first.
-- Apply only after the dedicated Supabase project is selected.

create extension if not exists pgcrypto;

create type public.app_role as enum ('ADMIN', 'DISPATCHER', 'DRIVER', 'UNASSIGNED');
create type public.driver_status as enum ('PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE');
create type public.device_status as enum ('PENDING', 'ACTIVE', 'REVOKED');
create type public.trip_status as enum ('ASSIGNED', 'ACCEPTED', 'STARTED', 'RUNNING', 'WAITING', 'COMPLETED', 'CANCELLED');
create type public.tariff_mode as enum ('METER', 'HOURLY', 'AIRPORT', 'OUTSTATION_ONE_WAY', 'OUTSTATION_ROUND_TRIP');

create table public.organizations (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  brand_name text not null default 'SBS Travels',
  created_at timestamptz not null default now()
);

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  full_name text,
  phone text,
  role public.app_role not null default 'UNASSIGNED',
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index profiles_org_idx on public.profiles(organization_id);
create index profiles_role_idx on public.profiles(role);

create table public.drivers (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid unique not null references public.profiles(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  driver_code text not null,
  status public.driver_status not null default 'PENDING',
  activation_required boolean not null default true,
  activated_at timestamptz,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, driver_code)
);

create index drivers_org_status_idx on public.drivers(organization_id, status);

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  driver_id uuid not null references public.drivers(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  device_fingerprint text not null,
  device_name text,
  app_version text,
  status public.device_status not null default 'PENDING',
  activated_at timestamptz,
  revoked_at timestamptz,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  unique (organization_id, device_fingerprint)
);

create index devices_driver_idx on public.devices(driver_id);

create table public.tariffs (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete restrict,
  name text not null,
  mode public.tariff_mode not null,
  version integer not null default 1,
  rules jsonb not null default '{}'::jsonb,
  is_active boolean not null default false,
  effective_from timestamptz not null default now(),
  effective_until timestamptz,
  created_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  unique (organization_id, mode, version)
);

create index tariffs_active_idx on public.tariffs(organization_id, mode, is_active);

create table public.trips (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete restrict,
  driver_id uuid references public.drivers(id) on delete set null,
  assigned_by uuid references public.profiles(id) on delete set null,
  status public.trip_status not null default 'ASSIGNED',
  customer_name text,
  customer_phone text,
  pickup_address text,
  pickup_lat double precision,
  pickup_lng double precision,
  destination_address text,
  destination_lat double precision,
  destination_lng double precision,
  start_lat double precision,
  start_lng double precision,
  end_lat double precision,
  end_lng double precision,
  tariff_id uuid references public.tariffs(id) on delete set null,
  tariff_snapshot jsonb,
  start_otp_hash text,
  scheduled_at timestamptz,
  accepted_at timestamptz,
  started_at timestamptz,
  completed_at timestamptz,
  distance_km numeric(12,3) not null default 0,
  waiting_minutes numeric(12,2) not null default 0,
  base_fare numeric(12,2) not null default 0,
  distance_fare numeric(12,2) not null default 0,
  waiting_fare numeric(12,2) not null default 0,
  extra_fare numeric(12,2) not null default 0,
  total_fare numeric(12,2) not null default 0,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index trips_org_status_idx on public.trips(organization_id, status);
create index trips_driver_status_idx on public.trips(driver_id, status);
create index trips_created_idx on public.trips(created_at desc);

create table public.trip_events (
  id bigint generated always as identity primary key,
  trip_id uuid not null references public.trips(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  event_type text not null,
  from_status public.trip_status,
  to_status public.trip_status,
  actor_profile_id uuid references public.profiles(id) on delete set null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index trip_events_trip_idx on public.trip_events(trip_id, created_at);

create table public.meter_events (
  id bigint generated always as identity primary key,
  trip_id uuid not null references public.trips(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  sequence_no bigint not null,
  captured_at timestamptz not null,
  latitude double precision,
  longitude double precision,
  accuracy_m double precision,
  speed_mps double precision,
  distance_delta_m double precision not null default 0,
  waiting_delta_seconds double precision not null default 0,
  fare_snapshot jsonb,
  client_event_id uuid not null default gen_random_uuid(),
  created_at timestamptz not null default now(),
  unique (trip_id, sequence_no),
  unique (trip_id, client_event_id)
);

create index meter_events_trip_time_idx on public.meter_events(trip_id, captured_at);

create table public.invoices (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid unique not null references public.trips(id) on delete restrict,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  invoice_number text not null,
  subtotal numeric(12,2) not null default 0,
  extra_total numeric(12,2) not null default 0,
  total numeric(12,2) not null default 0,
  breakdown jsonb not null default '{}'::jsonb,
  issued_at timestamptz not null default now(),
  unique (organization_id, invoice_number)
);

create index invoices_org_idx on public.invoices(organization_id, issued_at desc);

-- Helper functions used by RLS. SECURITY DEFINER prevents policy recursion.
create or replace function public.current_profile()
returns public.profiles
language sql
stable
security definer
set search_path = public
as $$
  select p from public.profiles p where p.id = auth.uid() limit 1;
$$;

create or replace function public.current_org_id()
returns uuid
language sql
stable
security definer
set search_path = public
as $$
  select p.organization_id from public.profiles p where p.id = auth.uid() limit 1;
$$;

create or replace function public.current_role()
returns public.app_role
language sql
stable
security definer
set search_path = public
as $$
  select p.role from public.profiles p where p.id = auth.uid() limit 1;
$$;

alter table public.organizations enable row level security;
alter table public.profiles enable row level security;
alter table public.drivers enable row level security;
alter table public.devices enable row level security;
alter table public.tariffs enable row level security;
alter table public.trips enable row level security;
alter table public.trip_events enable row level security;
alter table public.meter_events enable row level security;
alter table public.invoices enable row level security;

-- Organization/profile visibility.
create policy "org members can read organization"
on public.organizations for select
using (id = public.current_org_id());

create policy "users can read own profile"
on public.profiles for select
using (id = auth.uid());

create policy "admins can manage organization profiles"
on public.profiles for all
using (organization_id = public.current_org_id() and public.current_role() = 'ADMIN')
with check (organization_id = public.current_org_id());

-- Drivers: admins/dispatchers manage; driver can read own driver row.
create policy "driver self read"
on public.drivers for select
using (
  organization_id = public.current_org_id()
  and (
    profile_id = auth.uid()
    or public.current_role() in ('ADMIN','DISPATCHER')
  )
);

create policy "admins manage drivers"
on public.drivers for all
using (organization_id = public.current_org_id() and public.current_role() = 'ADMIN')
with check (organization_id = public.current_org_id());

create policy "dispatchers update drivers"
on public.drivers for update
using (organization_id = public.current_org_id() and public.current_role() = 'DISPATCHER')
with check (organization_id = public.current_org_id());

-- Devices: driver sees own device; admins/dispatchers manage.
create policy "device access"
on public.devices for select
using (
  organization_id = public.current_org_id()
  and (
    driver_id in (select d.id from public.drivers d where d.profile_id = auth.uid())
    or public.current_role() in ('ADMIN','DISPATCHER')
  )
);

create policy "admins manage devices"
on public.devices for all
using (organization_id = public.current_org_id() and public.current_role() = 'ADMIN')
with check (organization_id = public.current_org_id());

create policy "dispatchers update devices"
on public.devices for update
using (organization_id = public.current_org_id() and public.current_role() = 'DISPATCHER')
with check (organization_id = public.current_org_id());

-- Tariffs: everyone in the organization can read active tariff configuration;
-- only admins can change authoritative tariff definitions.
create policy "members read tariffs"
on public.tariffs for select
using (organization_id = public.current_org_id());

create policy "admins manage tariffs"
on public.tariffs for all
using (organization_id = public.current_org_id() and public.current_role() = 'ADMIN')
with check (organization_id = public.current_org_id());

-- Trips: drivers see assigned trips; dispatch/admin see organization trips.
create policy "trip visibility"
on public.trips for select
using (
  organization_id = public.current_org_id()
  and (
    public.current_role() in ('ADMIN','DISPATCHER')
    or driver_id in (select d.id from public.drivers d where d.profile_id = auth.uid())
  )
);

create policy "dispatchers create trips"
on public.trips for insert
with check (
  organization_id = public.current_org_id()
  and public.current_role() in ('ADMIN','DISPATCHER')
);

create policy "dispatchers update trips"
on public.trips for update
using (
  organization_id = public.current_org_id()
  and public.current_role() in ('ADMIN','DISPATCHER')
)
with check (organization_id = public.current_org_id());

-- Driver trip mutations will be moved to controlled Edge Functions/RPCs;
-- direct driver updates are intentionally not granted here.

create policy "trip events visibility"
on public.trip_events for select
using (
  organization_id = public.current_org_id()
  and (
    public.current_role() in ('ADMIN','DISPATCHER')
    or trip_id in (
      select t.id from public.trips t
      where t.driver_id in (select d.id from public.drivers d where d.profile_id = auth.uid())
    )
  )
);

create policy "meter events visibility"
on public.meter_events for select
using (
  organization_id = public.current_org_id()
  and trip_id in (
    select t.id from public.trips t
    where public.current_role() in ('ADMIN','DISPATCHER')
       or t.driver_id in (select d.id from public.drivers d where d.profile_id = auth.uid())
  )
);

create policy "invoice visibility"
on public.invoices for select
using (
  organization_id = public.current_org_id()
  and (
    public.current_role() in ('ADMIN','DISPATCHER')
    or trip_id in (
      select t.id from public.trips t
      where t.driver_id in (select d.id from public.drivers d where d.profile_id = auth.uid())
    )
  )
);

-- Keep updated_at current on mutable tables.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger profiles_set_updated_at
before update on public.profiles
for each row execute function public.set_updated_at();

create trigger drivers_set_updated_at
before update on public.drivers
for each row execute function public.set_updated_at();

create trigger trips_set_updated_at
before update on public.trips
for each row execute function public.set_updated_at();
