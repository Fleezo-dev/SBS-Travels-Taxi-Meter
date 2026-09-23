create or replace function public.validate_trip_transition(
  p_from public.trip_status,
  p_to public.trip_status
)
returns boolean
language sql
immutable
set search_path = public
as $$
  select case
    when p_from = 'ASSIGNED' and p_to in ('ACCEPTED','CANCELLED') then true
    when p_from = 'ACCEPTED' and p_to in ('STARTED','CANCELLED') then true
    when p_from = 'STARTED' and p_to in ('RUNNING','CANCELLED') then true
    when p_from = 'RUNNING' and p_to in ('WAITING','COMPLETED','CANCELLED') then true
    when p_from = 'WAITING' and p_to in ('RUNNING','COMPLETED','CANCELLED') then true
    else false
  end
$$;

revoke execute on function public.validate_trip_transition(public.trip_status, public.trip_status)
from public, anon, authenticated;
