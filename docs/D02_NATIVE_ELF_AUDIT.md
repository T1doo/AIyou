# D02 actual APK native binary evidence

The original audit slice adds host ELF counterexamples and actual APK/LLVM cross-checking. The later SQLCipher Android4.19.1 candidate changes the pinned dependency/native bytes and keeps the strict gates, KDF, permissions and native-device test budgets. `LOCAL_ONLY` and the original plans are preserved. It does not close D02 runtime acceptance.

## Four checks remain separate

1. Actual APK ZIP alignment: fixed already installed Build Tools36.0.0 `zipalign -c -P 16 4`, on an immutable snapshot of the APK bytes read by the audit. Per-entry stored/compressed status and actual local payload offsets are also recorded.
2. ELF PT_LOAD alignment: `p_align >= 16384` and virtual-address/file-offset congruence modulo16384. Header fields are decoded according to ELF32/64 and declared byte order, not host architecture.
3. Android's [official RELRO endpoint formula](https://developer.android.com/guide/practices/page-sizes#check-relro): `(VirtAddr + MemSiz) % 0x4000 == 0`. It uses memory values, not Offset/FileSiz. Zero rounded-page collisions cannot override a literal FAIL. Absence of RELRO is reported NOT_APPLICABLE for this endpoint check, not application security/runtime acceptance.
4. Page expansion: floor(VirtAddr/page)..ceil((VirtAddr+MemSiz)/page), at4K and16K, compared separately against non-RELRO PF_W LOAD memory and SHF_ALLOC|SHF_WRITE section intervals. LOAD coverage includes padding; a section-aware gap is only absence of ALLOC section descriptions. Unknown/absent sections are explicit, not fabricated zero writable content. Raw RELRO can extend into LOAD page padding without structural invalidity; raw versus expanded LOAD uncovered ranges are recorded. All these are static bounds, not Android loading or universal crash/safety proof.

`scripts/d02_elf.py` refuses truncated/out-of-file tables and file spans, virtual endpoint overflow, invalid LOAD FileSiz/MemSiz, invalid LOAD alignment/congruence, duplicate critical headers, ambiguous multiple RELRO, invalid section-name bounds and overlapping header tables. Extended ELF numbering and >4096 program headers are explicitly unsupported by this bounded SQLCipher audit. NOBITS/zero-file-size memory does not require nonexistent file bytes; RELRO FileSiz may exceed MemSiz as in the official example. Unknown segment types are preserved; duplicates of noncritical types are not conflated with duplicate LOAD/RELRO.

## Actual tools and reproducibility

Existing CI484 inventory actually records NDK27.3.13750724,29.0.14206865,30.0.16248370 using already accepted android-sdk-license. Select only the fixed27.3 installation; never discover/download the latest NDK. Audit verifies its source.properties/localPackage/license reference and existing canonical accepted hash24333f8a63b6825ea9c5514f83c2829b004d1fee. Record actual llvm-readelf version, executable SHA, metadata SHAs and accepted license file before/after; no installation/yes/new acceptance.

Each actual APK member records SHA/size, ELF class/endian/machine, every raw PT_LOAD/PT_GNU_RELRO program-header byte string and values. The independent installed LLVM tool reads the exact same extracted bytes; its separate text rows must agree in type/order/flags/Offset/VirtAddr/PhysAddr/FileSiz/MemSiz/Align. Its full unmodified output is retained. A parser result without this actual LLVM check cannot be reported as LLVM-cross-checked.

Commands for an already installed/licensed SDK and an APK with a known source association:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_d02*elf*.py' -v
python3 scripts/d02_native_elf_audit.py audit --apk APK_PATH --sdk-root EXISTING_SDK --output NEW_OWNED_DIRECTORY --apk-source-sha EXACT_APK_SOURCE_SHA
python3 scripts/d02_native_elf_audit.py enforce-doc-endpoint --report NEW_OWNED_DIRECTORY/REPORT.json
```

The final command really exits nonzero for literal FAIL. The dedicated CI records/retains evidence and the actual APK even when this endpoint gate fails. No continue-on-error, binary patch or collision-based override makes it green. A changed dependency must pass the same literal formula on its actual new bytes. Ordinary engineering CI is distinct; successful audit generation/LLVM agreement is evidence validity, not16K runtime compatibility. Actual device execution remains NOT RUN.

## Historical 4.19.0 evidence and access boundary

Root's59 Python methods passed (26 ELF parser,11 APK/gate/fault preservation,22 prior); cached fixed4.19.0 AAR four members independently match actual GNU readelf2.44 headers. All4 LOAD alignment PASS, Android literal RELRO endpoint FAIL, checked expanded non-RELRO LOAD/allocated-write collisions0. This is cached AAR evidence, not an APK/device claim. Raw RELRO page padding caused an initial over-strict prototype containment rejection, which was corrected and covered by a genuine regression; the zero-collision/literal-failure distinction remains. Independent source review also exposed lost partial stdout/stderr on zipalign timeout. The corrected path preserves both streams and a failed report; actual owned-child timeout regressions use explicit synthetic SDK boundaries, not fabricated LLVM/device evidence.

Baseline APK source48472607644b057d0d2538d6aeae2285a5d443ae, original run38050377490/artifact11669013060, APK SHA5b463201d90c68449c07affb4807e2cf61c30bb07ca737ca20351b56234201b7: normal download preparation succeeded but supported local transfer failed once. No alternate transport/retry; root has not read that historical APK. New CI builds and audits a distinct actual APK with its own exact source/SHA/artifact. Do not replace the historical484 binary identity with a rebuilt one or claim it was downloaded. Current source preserves native dependencies/runtime; only actual reported member hashes can establish binary equality where available.

Historical single fourth-case run38050377524 actually timed out240s after8 completed opens and9th started. It is not rerun by this workflow. Full13, encrypted migration acceptance, external cross-PID persistence, production UI/Keystore,16K and physical performance remain unclosed. Minimum runtime opportunity is an already authorized supported accelerated Android environment or physical device with existing adb/SDK/accepted terms; no security enlargement/new terms is assumed.

## JNIString provenance

Publisher4.19 fixed9a5d685404489cbff14d4c46da81555de1a38787 and introduction49dad2e0a5d9d4f427df6bb9d9882baac0ebbe8f retain identical4704-byte JNIString.cpp, SHA433aea7d34b888104499f45e72372579b8a9a65488ed90571b7050f2495df65f. Introduction proves the publisher's source history, not its earlier adapted AOSP revision.

A normal official-source read identifies a specifically matching decoder candidate at [AOSP libcore fa5b565a3f6c6d7cbd6106ee8d360304c3a939a3](https://android.googlesource.com/platform/libcore/+/fa5b565a3f6c6d7cbd6106ee8d360304c3a939a3). C++ type/control-flow adaptations correspond to that Java decoder, but source similarity does not prove which revision the publisher used. Full fixed history/NOTICE could not be normally read in this research and was not retrieved through alternative routes. Exact adapted revision, AOSP-to-Harmony ancestor and applicable complete NOTICE remain UNKNOWN.

Previously obtained official Harmony root/classlib NOTICE at02970cb7227a335edd2c8457ebdde0195a735733 are reference material, not proof of that decoder's ancestry; that inspected String.java does not contain this decoder. No arbitrary Harmony component notices are substituted, no license asset replaced, no maintenance email/message sent. Known ASF/source headers and full Apache2 text remain packaged; no legal completeness claim.

## 4.19.1 candidate before actual APK CI

The actual pinned Maven AAR SHA256 is43fc10cae8f1f1059f0933e8942a1eb328989d13426378597cd4add1b34a34fd.
Independent GNU readelf2.44 on all four new AAR members agrees with every selected
raw program-header field. LOAD16K and strict RELRO endpoint checks pass; RELRO
endpoints are arm64-v8a0x204000, armeabi-v7a0x104000, x860x228000 and x86_640x224000.
This is AAR evidence, not an APK/LLVM/device result. The unchanged strict CI must
build a distinct actual APK, record its exact source/SHA, compare fixed LLVM rows
and run ZIP plus endpoint gates. The engineering packaging check pins all four
actual4.19.1 native member hashes in both app and instrumentation APKs.

All four native members differ from4.19.0. Upstream also changes the core gitlink
and driver type mapping; see the upgrade section in D02_STORAGE_CHECKPOINT.md.
The embedded core version remains4.19.0. Old encrypted database migration/readback,
full13 original cases, external cross-PID, and physical16KB acceptance remain
separate NOT RUN. JNIString's new publisher revision retains identical bytes;
the complete applicable NOTICE remains UNKNOWN. Historical RELRO FAIL and device
timeouts remain historical failures, rather than being relabeled by this upgrade.
