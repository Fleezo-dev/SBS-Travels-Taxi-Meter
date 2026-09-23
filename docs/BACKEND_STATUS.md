# Backend Status

## Live
- Supabase project: SBS Travels
- Region: ap-south-1
- Core schema migration applied
- Security helper hardening applied
- Driver activation Edge Function deployed

## Driver activation contract

POST `/functions/v1/driver-activate`

Authenticated driver supplies:
- driver_code
- device_fingerprint
- optional device_name
- optional app_version

The server checks:
1. JWT is valid.
2. User profile exists and is an active DRIVER.
3. Driver code belongs to that profile and organization.
4. Driver has been activated by an administrator.
5. Device is not bound to another driver.

The function then binds/activates the device.

## Next backend work
- Correct immutable audit-event table (trip_events currently requires a trip and will be separated from driver/device audit events).
- Server-side trip state transition function.
- Meter-event ingestion endpoint.
- Tariff seed/configuration.
- Invoice generation.
- Realtime subscriptions.
- Admin/dispatcher management surface.
