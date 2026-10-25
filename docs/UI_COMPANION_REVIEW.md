# Production interface and optional local companion

## Additional explicit local poses — 2026-10-25

The existing panel now also offers “一起想一想” and “打个哈欠”, using the
unchanged, reference-based `lulu_think.png` and `lulu_yawn.png` originals listed
in [artwork provenance](../assets/IP_USAGE.md). These are local companion
actions, never model-processing indicators or inferences from private records.
The existing finite-run ownership, quiet cancellation, reduced-motion,
background, power-save and close/owner retirement rules are reused. No record,
task, network, storage or permission capability is added. The bitmap cache has
five fixed poses, decoded lazily at 627px, bounded to about 7.5 MiB per consumer.

The existing pure state tests exercise both new poses, busy-action rejection,
background cancellation and stale completion/retired-panel isolation. The UI
gate additionally requires real button clicks, distinct native bitmap captures,
zero reduced-motion transforms, quiet/close cancellation and an unchanged
capture draft with zero saves. The new method and all original methods remain
mandatory. Its captures are controlled Compose/Robolectric evidence, not device
animation, TalkBack or a physical system-font test. Android execution and the
new captures are pending until the candidate's formal CI actually runs.

## Current two-action slice — 2026-10-23

Tested source: `f55f5fb8b4693c75725f2804281e2fe9a5b0af45`.
Its production `app/src/main` and `core` trees are unchanged from
`b4380bdb17e52681609a552c8542de1b705f199d`; subsequent revisions change the
controlled test and its diagnostic collection contract. **The complete formal
three-job acceptance has not passed on this two-action tree.**

### Artwork and actual interaction scope

`CompanionEntry` and the existing `CompanionDialog` use `LuluMascotAssets`.
The approved reference-based, AI-generated RGBA originals are bundled locally:
`lulu_hello.png` for Idle, `lulu_wave.png` for Greeting, and
`lulu_encourage.png` for Encouraging. Exact paths, dimensions, byte counts,
SHA-256 values and authorization limits are in [artwork provenance](../assets/IP_USAGE.md).
No reference screenshots or private reference material are redistributed.
These are three distinct still poses, not a sprite sequence or an open-source
character licence. Thinking and sleepy poses are not bundled or connected.

The panel offers explicit greeting and encouragement, quiet cancellation and
close. Finite Compose transforms accompany the real pose changes; they do not
establish anatomically continuous animation or device smoothness. Reduced motion
uses a static pose. Backgrounding cancels the active run; returning foreground
does not restart it. Quiet renders zero motion as soon as the active run clears,
without depending on a later animation reset. Closing or replacing the lifecycle
owner retires old callbacks. Companion visibility and motion preferences remain
separate from records. No record/task/storage/model/network or permission port
is added to the interaction state machine; LOCAL_ONLY and save semantics remain.

### Precise synchronization and collection contract

The actual JVM target is `CompanionSettingsPresentationTest.`
`finiteInteractionMotionStopsOnReductionAndBackgroundWithoutAutomaticReplay`.
Its host input holders are not proof that the dialog consumed their changes.
The test captures the real outer `SideEffect` parameters and also samples actual
Greeting/Encourage/Quiet button semantics: node count, Disabled and bounds.
Seven host publications each retain at most four frames. Completion requires
the expected owner/state/assets plus three unique buttons, disabled in the
background and enabled in the other Idle phases; fallback additionally requires
the real fallback tag exactly once before scrolling to it.

Old callbacks run only after the replacement owner's actual buttons are enabled.
Their before/after semantics are sampled at the same virtual time, without frame
advance or recovery polling, followed by Idle, zero-close and enabled assertions.
`consumed` and `settled` describe the same state-plus-semantics sample, not extra
multi-frame stability. Recorded bounds do not prove simultaneous visibility.
The six real-click action phases, 64/96 ms motion checks, original first+39-frame
natural-completion budget, pixel assertions and 512-observation cap remain.
The collector requires exactly one fresh actual method, complete unique phase
checkpoints, valid per-frame samples and five original named captures. Missing
evidence, unexpected confirmed semantics, skip/error or method/schema failure
cannot become a diagnostic pass. No production fix was inferred from a missing
test publication frame.

### Actual diagnostic evidence and remaining acceptance

- Earlier [run 40026725208](https://github.com/T1doo/AIyou/actions/runs/40026725208),
  exact `de0b0c14f0c868b75cafa8a9555e231250cba8a5`: one actual method failed
  at line 309, reading a disabled greeting button after the outer new-owner
  sample. It had no pre-callback button sample, so did not prove callback
  contamination. Its original failed XML/trace remain separate evidence.
- [Run 40028749065, attempt 1](https://github.com/T1doo/AIyou/actions/runs/40028749065),
  exact f55 source above: actual XML **1 test, 0 failures/errors/skips**;
  fixed-source admission, all 11 schema guards and collection passed. The 141-row
  trace directly records new-owner outer state with three disabled buttons at
  1232 ms, then three enabled buttons at 1248 ms before completion. Old callbacks
  leave all three enabled at the same 1248 ms; Idle/zero-close assertions pass.
  Seven publication frame counts are 1/2/1/1/1/2/1. This is one controlled run,
  not proof of every earlier scheduler trajectory.
- Fallback is actually consumed at 1264 ms with one real tag; its subsequent
  real greeting click, active run and static-pixel equality assertion pass.
  No separate fallback PNG was saved. Five personally inspected 112 × 112 PNGs
  cover Idle, Greeting, Encouraging, quiet Idle and background Idle at
  16/128/320/368/528 ms. They are controlled-clock captures, not a continuous
  animation recording. The latter two are byte-identical to the Idle capture.
- [Original artifact 12533159951](https://github.com/T1doo/AIyou/actions/runs/40028749065/artifacts/12533159951):
  64,628 bytes; SHA-256
  `841174726b543d0a1b9ad8844f9e92fe56be4a141e3a9ac3085a482c77c7732f`.
  All 13 ZIP entries passed size/hash/CRC and safe-path checks. Original XML:
  509 bytes, SHA-256 `90a1c24b645d3c9af2e1fd7333620b363a13583307470de0741b63cf78d6c218`;
  trace: 134,594 bytes, SHA-256 `2adb58083b7948051557258710cf0c1af7fbe8e906e334b3fc98b644351cb163`.

Only this fixed method ran. The formal host-contract, Android and Preview jobs
were skipped by the diagnostic selector. Full Compose, APK, lint, instrumentation
compilation/audits and all Preview checks still need the complete formal path on
the candidate tree; older full-suite results cannot cover this delta. Device
audio/SQLCipher/OCR, IME/focus/scroll dynamics, TalkBack and performance remain
unverified. Diagnostic success does not approve publication or complete D00–D09.

## Historical neutral-companion slice

The following records the earlier neutral/white-blue implementation and its own
test inventory. Its 92-test/386-render results and pixel review are historical,
not current two-action results or approval of the current appearance.

The production `LocalRecordsScreen` now shares its page header with the task/demo
screen. The existing pale-blue canvas, white record surfaces and readable blue
controls remain; warm decorative colors belong only to the original neutral
capybara. The compact brand keeps 爱忆 primary and AIyou secondary without adding
a second line to the normal top bar. Empty task pages no longer repeat empty
future/completed section headings.

Settings has bounded, centered groups for display, local privacy, unopened model
connections, attribution and optional synthetic examples. Both display choices
are persisted outside the encrypted record tree via platform AtomicFile on an IO
dispatcher, then read back before UI confirmation. Failed reads preserve the
file and keep the companion hidden; failed writes retain confirmed values and
offer retry. These ordinary preferences contain no record text, key or consent.

The companion is optional and local. Its neutral geometric asset can be replaced
through `MascotAssetProvider`; no reference image is redistributed. A named,
56 dp button opens a small greeting/quiet panel. Decorative drawing has no
independent accessibility target. Switch rows each own one toggle action, and
the changing greeting has a polite live region.

Successful-save feedback originates only after the real ViewModel save and
record-list readback, using a transient sequence number. It is consumed once by
the active presentation; failure, draft persistence, demo mode and returning
from settings cannot generate it. The finite nod respects reduced motion,
resumed lifecycle, platform animation enablement and power saver. No idle loop,
network request, task mutation or emotional-pressure mechanism is added.

## Review evidence and limits

- Independent read-only source review found no new P0/P1 blocker. Its small
  greeting accessibility suggestion was applied.
- Full application JVM suite: 92 tests; data evidence 21; domain 3; design-system
  7. Zero failures/errors/skips in the final full-suite XML.
- Six actual ViewModel host queue tests check failure before readback produces
  no save-feedback ID, and a successful retry/readback produces one.
- New UI tests cover actual production routes, settings return, a single switch
  callback at 320 dp/200%, reachable licence/retry actions, preference failure
  and cold storage-instance reload, finite motion and no remount replay.
- The final 320 × 640 dp/200% interaction test reaches the companion greeting,
  closes its dialog, scrolls to production save and preserves the draft.
- All 386 Compose renders and screenshot validations passed. Independent pixel
  review inspected 11 representative images, including large-font, landscape,
  wide, settings, companion and failed-save states; no visual blocker was found.
- The actual motion gate is exercised in Robolectric using lifecycle changes,
  framework animation scale, settings observer and power-saver broadcast.
  This remains JVM simulation, not a physical Android settings experiment.
- The first full UI run caught a top-bar-height regression in the existing
  controlled-viewport-shrink test. The compact brand fixed it; the original
  navigation algorithm and assertion were not relaxed.
- Initial manual-clock animation assertions coalesced changes. Per-frame
  synchronization passes; the earlier red result alone is not evidence of a
  production replay defect. Consuming a pulse before suspension remains a
  conservative timing improvement.

Render verification and independent pixel review are recorded in the delivery
report. Generated render references are smoke evidence, not approved goldens.
Physical TalkBack, IME, API 26 filesystem/Keystore/native open, 16 KB page size,
60/120 Hz performance and the full D02 acceptance gate remain NOT RUN/open for
this APK. Historical device evidence belongs to its original source commit.

Platform references:
[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile),
[ValueAnimator animation enablement](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()).
