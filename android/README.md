# SBS Travels Taxi Meter — Android

This module is being built against the SBS Travels Supabase backend.

Security rule: activation is mandatory. The production driver workflow must not expose the meter until the authenticated driver account, driver status, and device binding have been validated by Supabase.

Planned next layers:
1. Supabase Auth/session
2. driver-activate integration
3. persistent device binding
4. realtime trip assignment
5. OTP trip start
6. GPS meter + offline event queue
7. trip completion/invoice
