# Supabase backend

This directory contains database migrations and backend definitions for SBS Travels.

## Planned Supabase services

- PostgreSQL database
- Supabase Auth
- Row Level Security
- Realtime
- Edge Functions
- Storage only where required

## Secrets

Never commit:
- Supabase service-role key
- database password
- Android signing credentials
- production API secrets

Client-safe Supabase URL/anon key may be generated during app configuration, but production secrets remain outside Git.

## Migration policy

Database changes are versioned under `supabase/migrations/`.

Migrations should be reviewed before applying to production.

## Initial schema

The first migration establishes organization users, drivers, devices, tariff versions, trips, trip events, meter events, and invoices. RLS policies will be tightened as role/organization rules are finalized.
