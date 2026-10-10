# Host queue regression boundary

This standalone JVM test project compiles the actual app ViewModel and pure
model/domain sources via a Sync task, with real coroutines and JUnit. Small
Application/lifecycle/repository stand-ins isolate controlled queue failures.
It is never included in the app's Android project or APK dependencies.

Run `scripts/gradle.sh --project-dir checks/record-queue test` from repository root.
Three event-ordering regressions cover a prior failed draft write, genuine save
failure and failed readback after commit, including later input and identity
preserving retries. The XML must contain three passing non-skipped testcases.
This is host queue behavior evidence; Android lifecycle, encryption, Keystore,
Room, durability and device performance require the separate actual native tests.
