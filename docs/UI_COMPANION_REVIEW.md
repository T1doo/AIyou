# Production interface and optional local companion

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
