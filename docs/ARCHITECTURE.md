# SBS Travels — Architecture

## High-level

Android Driver App
→ Supabase Auth
→ PostgreSQL / RLS
→ Realtime
→ Edge Functions
→ Dispatcher/Admin clients

Android also maintains a local Room database for active trips, meter events, and an outbound sync queue.

## Android layers

### Presentation
Jetpack Compose + Material 3.

### Domain
- Meter engine
- Fare calculation
- Trip state machine
- Permission/setup coordinator
- Activation policy
- Sync policy

### Data
- Room
- Supabase client
- Realtime subscriptions
- Repository layer

### Services
- Foreground location/meter service
- Floating overlay service
- Notification handling
- Connectivity/sync worker

## Backend responsibilities

Supabase is authoritative for:
- User/role records
- Driver activation
- Device registration
- Tariff versions
- Trip assignment/state
- Server-generated audit events
- Invoice records
- Realtime dispatch updates

Sensitive operations should use Edge Functions where direct client access would be unsafe.

## Security model

Use Supabase Auth plus PostgreSQL Row Level Security.

Principles:
- DRIVER can read/update only permitted own records.
- DRIVER cannot change authoritative tariff definitions.
- DRIVER cannot assign trips.
- DISPATCHER can manage operational trips and assigned drivers according to policy.
- ADMIN can manage organization-wide configuration.
- Service-role credentials are server-only and never shipped in the APK.

## Data flow: trip assignment

Dispatcher
→ create/assign trip
→ PostgreSQL transaction
→ Realtime notification
→ driver app
→ driver accepts
→ server validates transition
→ meter starts after required authorization/OTP
→ local meter events
→ sync
→ completion
→ invoice

## Data flow: offline trip

GPS
→ meter engine
→ Room
→ sync queue

When connectivity returns:
→ authenticated sync
→ server validates event/order
→ records accepted events
→ local queue marks synced

## Tariff strategy

Tariffs are versioned. A trip references the tariff version used when it starts so later tariff changes do not rewrite historical fares.

## Auditability

Important actions should generate immutable or append-only events:
- activation/deactivation
- device binding
- trip assignment
- trip state transition
- tariff publication
- manual fare adjustment
- trip completion
- invoice creation
