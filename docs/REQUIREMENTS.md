# SBS Travels — Requirements Baseline

## Driver app

### Activation
- Activation is mandatory before the driver can use the meter.
- Device activation is tied to the backend.
- Unauthorized/deactivated devices cannot start controlled trips.
- Activation state must not be bypassed by a client-only flag.

### Permissions
The app must guide the driver through required Android permissions:
- Precise location
- Background location where required
- Notifications
- Foreground location service
- Display over other apps for the floating meter
- Battery optimization handling where applicable

The app must explain Android-controlled restrictions rather than attempting to bypass them.

### Meter
- Start, run, pause/wait, and complete a trip.
- GPS distance calculation.
- Waiting-time calculation.
- Base fare + per-km + waiting charge.
- Configurable extra charges.
- Persist active trip locally.
- Continue core metering during temporary network loss.
- Restore/reconcile an active trip after process/app interruption.
- Prevent accidental exit during an active trip.
- Two-step/guarded back behavior where appropriate.

### Floating meter
- Meter can remain visible over other apps during an active trip.
- Driver can use navigation/phone/other apps while the meter service continues.
- Overlay permission is checked before enabling this feature.

### Tariff/charges
The backend must support configurable rules for:
- Normal/local meter
- Hourly rental
- Airport
- One-way/outstation
- Round trip
- Hill charges (optional)
- Night charges (optional)
- Parking/toll/other extras where applicable

The exact production rates are configuration data, not hard-coded business truth.

### Trip security
- Dispatcher creates/assigns a trip.
- Driver receives the assignment.
- Driver accepts/starts the trip.
- OTP/PIN may be required to start a controlled trip.
- Server validates state transitions.

### Receipt
At completion:
- Distance
- Running fare
- Waiting time/charge
- Optional charges
- Final total
- Trip/customer details as configured
- Shareable receipt/invoice

## Dispatcher/Admin

### Drivers
- Create/manage drivers.
- Activate/deactivate driver accounts.
- Bind/revoke devices.
- View driver availability/status.
- Control role permissions.

### Trips
- Create trip.
- Assign driver.
- Monitor trip state.
- Reassign when permitted.
- View trip event history.
- View completed trips and invoices.

### Tariffs
- Configure tariff versions/rules.
- Publish effective tariff configuration.
- Drivers receive controlled configuration from backend.
- Driver cannot edit authoritative tariff values.

### Security
Roles planned:
- ADMIN
- DISPATCHER
- DRIVER
- UNASSIGNED

Server-side authorization must enforce role and ownership rules.

## Trip state machine

Planned baseline:

ASSIGNED → ACCEPTED → STARTED → RUNNING → WAITING ↔ RUNNING → COMPLETED

Invalid state transitions must be rejected by server-side logic.

## Offline model

Android:
GPS/meter event → local Room → sync queue → Supabase

The server remains the authoritative record after synchronization. Conflicts must be resolved deterministically and audited.

## UI direction

Brand: **SBS Travels**

Visual direction carried forward from the existing meter project:
- Black / white / red
- Clean professional taxi-meter presentation
- Main meter screen kept uncluttered
- Secondary functions in the hamburger/menu area
- No unnecessary APK/download controls in the driver UI
