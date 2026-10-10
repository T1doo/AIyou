# Bounded native storage experiment

These are host-only test tools. They are not an app module or APK dependency.
The two package XML files were produced by the official Android repository
32.4.1 `InstallerUtil.writePackageXml` API from the fixed stable package entries,
then independently reloaded with no downloader or remote source. They retain
the full official license, image type details and emulator minimum dependency.
They are metadata, never an acceptance record or SDK binary.

`cached-jars.json` identifies the 13 pinned, byte-verified JARs needed by the
actual Java helper. `ExistingSdkLicense` parses this metadata with the official
schema API and calls the official `License.checkAccepted` against the installer
machine's existing SDK root before any payload transfer. It never calls
`setAccepted`; absent acceptance stops installation. On the SDK-less cloud
machine, real javac succeeded and this check returned exit 3, preserving the
explicit NOT RUN reason. No SDK package was downloaded or installed there.

The CI experiment uses only stable emulator 37.2.12 and AOSP API 35 default
x86_64 image revision 2, with the canonical existing license hash
`24333f8a63b6825ea9c5514f83c2829b004d1fee`. Its official fixed archives are
size/SHA1 checked and receive recorded actual SHA256 values. The experiment
refuses existing package targets, checks path containment, preserves archive
executable/symlink metadata, and registers the exact generated package XML.
It changes no license records, KVM permissions, TLS policy or credentials.
An unexpected package layout or CLI incompatibility fails rather than guessing.

The [official emulator documentation](https://developer.android.com/studio/run/emulator-commandline#common)
calls `-accel off` unsupported and very slow, and requires
`ANDROID_I_WANT_MY_TCG=yes`. This explicit opt-in is for software emulation,
not license acceptance. One owned headless AVD gets a 720-second boot deadline;
each actual instrumentation command gets at most 240 seconds. Downloads,
boot and testing share a 22-minute experiment budget after acceptance checking;
commands are capped by its remaining time and reserve time for owned cleanup.
The workflow is bounded to 40 minutes and preserves normal failure evidence.
External cancellation or a runner failure cannot guarantee cleanup/artifact
upload; missing terminal results remain NOT RUN. It makes no supported-device
performance claim. Only its own emulator process may be terminated; existing
processes, ADB servers, unknown locks and unrelated test data are retained.

Actual runtime API, ABI and page size must be measured before testing. Thirteen
encrypted-storage methods require 13 start/pass terminal statuses, a completed
runner and zero skips/errors. The separate synthetic restart test requires
explicit write → force-stop → read in distinct process IDs, with no reinstall
or data wipe between phases, then removes only its own UUID test namespace.
Passing this test would verify repository persistence across instrumentation
processes, not a production UI restart or physical hardware behavior.

`inspect-d02-apks.py` separately verifies actual packaged license bytes and
SQLCipher ABI inventory, then uses installed Build Tools 36.0.0
[`zipalign -c -P 16 4`](https://developer.android.com/tools/zipalign).
ZIP alignment is distinct from ELF RELRO arithmetic and actual native load.
The known literal RELRO FAIL remains documented; neither these tools nor a
4 KB simulated device can certify 16 KB compatibility.

The original b42597a experiment actually booted and installed both APKs. Its
shared 240-second command ended after three passing cases and the fourth start;
the other nine never started. The fourth did not receive 240 seconds alone.
Cross-process,16KB and physical-device checks remain NOT RUN.

The explicitly authorized follow-up isolates only
`fiveAcknowledgedSavesSurviveSeparateDatabaseReopens`: five original save/reopen
rounds, ten repository opens, ten explicit closes and ten original entry queries.
`--targeted-fourth` retains the same 240-second instrumentation and 22-minute
overall ceilings. It opts into test-only per-step monotonic wall and aggregate
process-CPU timings, bounded own-process Java thread samples for slow steps,
continuous filtered logcat and bounded host/device load/CPU-pressure snapshots.
Observer failures and incomplete traces cannot be called complete diagnostics.
Every slow snapshot has an explicit sequence, original operation Java-thread
identity, declared thread count and capture-error field; missing/duplicate or
failed snapshots are rejected. The original operation thread is prioritized
within the 32-thread cap. Java thread IDs are not Linux TIDs; log emission TIDs
are labeled separately. At least 75 seconds are reserved for the bounded owned
observers, logcat, emulator and result footer. Archive reads have a tested own-
process Linux timer capped by remaining budget and 240 seconds, restoring its
handler and refusing to replace an existing timer. No other process is signaled.
Job logs print only this new experiment's synthetic filtered trace, summary
and health excerpts, capped at 512 KiB/1,024 lines per file. Oversize excerpts
are explicitly TRUNCATED with original byte/line count and SHA256; the complete
original logs remain in the artifact. A truncated excerpt is not a complete
trace. Unfiltered device logcat and arbitrary files are not printed by this tool.
Tracing adds observer overhead; wall minus aggregate CPU is not exact wait time.
First-new-file, same-process reopen and new-process existing-file open labels
describe file/process history, never measured OS cold/warm cache behavior.
Ten repository opens are not proof of exactly ten native SQL connections.

The exact reviewed request file `targeted-fourth-request-20261010.json` starts
one push-triggered attempt on the existing development branch; ordinary later
source pushes do not trigger this path. A manual run is also available but is
not automatically repeated after failure. The original full runner remains
available explicitly and is not the follow-up workflow's default. Before the
request is pushed, this follow-up's device execution is NOT RUN. Host observer
and parser tests do not substitute for SQLCipher native execution.
