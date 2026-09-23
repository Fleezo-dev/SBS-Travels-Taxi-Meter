# SBS Travels — Taxi Meter + Driver Dispatcher

SBS Travels is a backend-connected taxi meter and driver dispatch platform.

## Product

**SBS Travels → Taxi Meter + Driver Dispatcher → Supabase backend → Android driver app**

The Android driver app provides a reliable GPS-based taxi meter, background/foreground operation, floating meter overlay, trip workflow, receipts, and driver activation. The dispatcher/admin backend controls drivers, trips, tariffs, devices, and permissions.

## Core principles

- Backend is the authority for driver activation, assignments, tariff configuration, and protected administrative actions.
- A running trip must survive temporary network loss; local Android persistence is used and data syncs when connectivity returns.
- Driver UI stays simple; secondary/admin functions live behind the appropriate menus.
- Security is enforced server-side with authentication and Row Level Security, not only by hiding UI controls.
- Existing Get Taxi Meter work is treated as a reference; this repository is a clean SBS Travels implementation.

## Planned stack

- Android: Kotlin + Jetpack Compose + Material 3
- Local persistence: Room
- Location: Fused Location Provider + foreground service
- Backend: Supabase PostgreSQL + Auth + Realtime + Storage as needed
- Server logic: Supabase Edge Functions
- Source control / CI: GitHub

## Product areas

1. Driver activation and device binding
2. Driver authentication
3. Dispatcher trip assignment
4. Trip state machine
5. GPS meter engine
6. Waiting and extra-charge handling
7. Configurable tariffs
8. Floating meter overlay
9. Offline/recovery synchronization
10. Fare receipt/invoice
11. Driver history
12. Admin/dispatcher controls
13. Realtime trip updates
14. Audit/event history

## Repository status

Initial architecture and database foundation are being established. Production credentials and secrets must never be committed.
