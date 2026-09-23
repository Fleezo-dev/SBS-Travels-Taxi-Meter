-- RLS/performance hardening: authenticated-only policies, cached auth lookups,
-- and covering indexes for foreign keys.

revoke execute on function public.current_profile() from public;
revoke execute on function public.current_org_id() from public;
revoke execute on function public.current_role() from public;
revoke execute on function public.set_updated_at() from public;

-- Policies are recreated with explicit authenticated scope and SELECT-wrapped
-- auth helpers so PostgreSQL can evaluate them once per statement.
-- See production migration applied to Supabase: rls_performance_hardening.

create index if not exists audit_events_actor_profile_idx on public.audit_events(actor_profile_id);
create index if not exists meter_events_organization_idx on public.meter_events(organization_id);
create index if not exists tariffs_created_by_idx on public.tariffs(created_by);
create index if not exists trip_events_actor_profile_idx on public.trip_events(actor_profile_id);
create index if not exists trip_events_organization_idx on public.trip_events(organization_id);
create index if not exists trips_assigned_by_idx on public.trips(assigned_by);
create index if not exists trips_tariff_idx on public.trips(tariff_id);
