alter table public.trips
  add column if not exists load_otp_hash text,
  add column if not exists load_otp_used_at timestamptz;

create index if not exists trips_load_otp_hash_idx
  on public.trips(load_otp_hash)
  where load_otp_hash is not null;
