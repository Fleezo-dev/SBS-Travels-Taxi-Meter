create table public.audit_events (
  id bigint generated always as identity primary key,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  actor_profile_id uuid references public.profiles(id) on delete set null,
  event_type text not null,
  entity_type text,
  entity_id uuid,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index audit_events_org_time_idx on public.audit_events(organization_id, created_at desc);
create index audit_events_entity_idx on public.audit_events(entity_type, entity_id);

alter table public.audit_events enable row level security;

create policy "admins and dispatchers read audit events"
on public.audit_events for select
using (organization_id = public.current_org_id() and public.current_role() in ('ADMIN','DISPATCHER'));

create policy "drivers read own audit events"
on public.audit_events for select
using (organization_id = public.current_org_id() and actor_profile_id = auth.uid());

revoke insert, update, delete on public.audit_events from anon, authenticated;
