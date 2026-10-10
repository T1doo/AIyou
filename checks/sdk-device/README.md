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

Native execution for these new tools is pending their own CI run. Source
review, metadata discovery, Java compilation and timeout-log retention do not
turn that pending gate into PASS.
