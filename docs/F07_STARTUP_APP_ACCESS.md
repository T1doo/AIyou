# Startup and manual app access lock

Original plan §5.5/F07 includes an app lock. This slice provides an optional
startup access check using the device's existing secure screen lock, plus an
explicit 立即锁定 action. It starts disabled. Both enabling and disabling require
a fresh system credential result. It creates no app PIN, device credential,
biometric enrollment, permission, dependency or Room schema migration.

The Activity reads the separate versioned private setting before constructing a
records ViewModel or opening the records database. A new admitted memory-only
session owns its own records/metadata ViewModel store. Rotation retains the
access owner; process recreation cannot restore an authenticated result. The
single-task launcher avoids parallel Activity access owners in ordinary launches.

Before a settings transition or explicit lock, the records actor must be idle:
all queued writes have finished, the current input has a confirmed durable draft,
and no editor, picker, uncertain save or pending operation may be discarded.
Freezing admission and closing the queue use the same monitor as every enqueue.
The next session waits for the previous actor and actual storage-close boundary.
A process-level barrier holds only close acknowledgements so a replacement
Activity cannot forget an old owner's pending or failed close. A failed close
acknowledgement cannot admit a new session. Old source callbacks,
settings actions and credential results cannot initiate new work through retired
owners.

Preference writes use compare-and-set versions, AtomicFile and exact readback.
Unreadable, malformed, oversized, incomplete or uncertain settings keep records
closed. A lost write acknowledgement is resolved by an explicit fresh read;
it never silently defaults to disabled or repeats the previous write. Cancellation
of a settings verification can explicitly return to the previously admitted
access without changing its confirmed setting. Startup cancellation has no such
return privilege.

The existing framework credential confirmation intent is used without adding
biometric permissions or libraries. Android documents this API as deprecated
since API 29; the recommended BiometricPrompt path is outside this permission-
preserving slice. Unavailable device authentication fails closed; the app does
not open credential setup or provide a bypass. See the [Android KeyguardManager
reference](https://developer.android.com/reference/android/app/KeyguardManager#createConfirmDeviceCredentialIntent(java.lang.CharSequence,%20java.lang.CharSequence)).

The access gate owns this Activity's FLAG_SECURE while protection is enabled or
admission is unconfirmed. The setting explains the screenshot/recents restriction.
Platform/OEM enforcement still requires a device check.

## Deliberate limits and acceptance

This is startup/manual access control. Going to the background preserves the
current admitted session; it does not automatically lock. This does not migrate
or authentication-bind the database key and is not the plan's optional strong
privacy mode. No physical memory-erasure, root resistance, OEM screenshot,
TalkBack or device credential-flow claim is made from JVM tests.

Host checks compile the actual controller and records actor, including queued
draft failure, retired admission, stale results and a controlled storage-close
barrier. File/Compose tests use real AtomicFile and production UI, with a synthetic
credential intent/ActivityResultRegistry boundary. A production Activity startup
regression checks that an enabled lock keeps the records directory absent across
recreation. These are JVM checks, not device, Room, SQLCipher or Keystore execution.
CI retains the eight targeted app test methods' actual XML and verifies their
unique names before running the full suite. The existing Android SDK license and
device acceptance boundaries remain unchanged.

## Preference operation ownership (P2)

A controlled dual-owner host regression on `bc36f8a` reproduced an admitted
blocking enable finishing after its owner retired, while a replacement owner had
already read disabled. This is a synthetic interleaving, not a reported user
incident. The identical regression now requires the replacement to remain closed,
read the committed enabled value and require its own system verification.

Every production controller shares a process-owned preference operation mutex.
Initial/retry reads and the entire write/readback execute inside that boundary on
IO. Ownership is checked after acquiring it. A queued retired write is rejected;
an already executing blocking write may finish, and replacement readers wait for
its physical completion before reading the actual file. Cancellation alone is
not treated as acknowledgement that a blocking store operation stopped.

The order is operation mutex, brief ownership check, then file-store monitor.
Disposal takes only the ownership monitor and does not wait for disk. No caller
waits for the operation mutex while holding the ownership monitor. A failed write
releases the mutex so an explicit fresh read can resolve a lost acknowledgement;
it does not permanently poison admission. Tests cover queued cancellation,
committed-write/lost-ACK recovery and the same scheduling against real AtomicFile.
