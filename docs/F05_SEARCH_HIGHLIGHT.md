# Current-original search result highlight

Plan §10.4 requires original-text verification and highlighting of local search
results. The existing matcher supplies original Unicode code-point ranges; the
list now marks the first verified match in each current-original result card.
The mark uses the existing palette, bold and underline. It does not change
matching, ranking, filters, image-grid mode, citation offsets or the full reader.

The display helper checks the exact entry object, quote and original range before
creating a display-only UTF-16 span. Its marked excerpt starts at the first visible
character of the verified match, includes at most 80 original code points and
adds unmarked omission ellipses. A long match can be only partially displayed;
the full original remains available through 阅读全文. Filter-only and whitespace-only
results have no invented word highlight. Source and query text are never
normalized for display. No new storage, permissions, dependency, AI call or cache
is involved; the processing policy remains unchanged.

Existing current-search ownership checks still hide revoked results and reject
old open actions after query, source-version, deletion or page changes. The
highlight helper adds no worker, persistence or restoration path.

## Reproducible checks

- `python3 scripts/jvm-gradle.py -p checks/record-queue :test --tests com.t1doo.aiyou.records.OriginalSearchPreviewTest`
  compiles the actual display helper and matcher. Five assertions-based methods
  cover supplementary characters, folded original spelling, combining marks,
  exact whitespace, long-hit clipping, filter-only display and invalid snapshots.
- `scripts/gradle.sh :app:testDebugUnitTest --tests 'com.t1doo.aiyou.LocalOriginalSearchPresentationTest.searchCard*'`
  exercises actual app cards, styles, source opening, clear, result order, source
  revision/deletion and a 320 dp / 2× font layout. CI retains these two methods'
  exact XML before the full app suite and verifies their unique names/counts.
- `python3 scripts/verify-test-results.py --queue --ui` requires the new methods
  in actual XML along with the prior regressions. Do not combine partial/stale
  XML from different runs into a claimed full pass.

Android device, TalkBack, OEM font rendering and performance remain separate
acceptance conditions. JVM Compose layout checks and Preview smoke renders are
not device evidence. A device check should search a long current original,
confirm the first marked fragment is visible at large font, open the complete
unchanged text, then edit/delete the source and confirm the old card cannot return.
