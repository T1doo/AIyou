# Native white-blue visual slice

## Branch and scope

- Base: `4f547c09b1b5c6674060a9fff6527768fee11b27`, verified against remote `codex/d03-short-audio-design-20261020` before branching.
- GitHub compare confirms the base is 335 commits ahead of `main`, behind 0, merge base `7383e502418f5f9fc0c540ef2072742359343d40`.
- Independent branch: `dev/ui-polish`. Normal pushes only. No deployment, release, SDK installation, new dependency, new font, credential setup or new permission.
- Both original product documents were read completely and preserved. Executor skill catalog was empty; no native Android-specific skill was available in the session catalog.
- `MainActivity.kt`, `LocalRecordsScreen.kt`, `UnifiedKeywordDialog.kt`, controllers, persistence, model authorization and manifests are untouched. The unified-query functionality branch is not included.
- `Components.kt` changes only its private, content-only `ComponentCard` wrapper and obsolete imports; business component parameters, actions, status text, data and rendering branches are unchanged.

## Visible changes

| Element | Base | This slice |
| --- | --- | --- |
| Canvas | `#F0F6FE` | `#F5F8FD`, whiter canvas |
| Muted surface | `#E7F1FC` | `#EDF3FA` |
| Selected container | same as muted surface | `#E0EEFC`, separate selected hierarchy |
| Status containers | muted blue for errors and success | separate pale red and green semantic surfaces |
| Page title | 24/32 sp | 28/36 sp |
| Section | 18/24 sp | 20/28 sp |
| Body | 16/24 sp | 16/26 sp |
| Supporting text | 14/20 sp | 14/22 sp |
| Panel/input radius | 16/20 dp | 20/24 dp |
| Section separation | 16 dp | 20 dp |
| Shared panels | private Material Card | reusable `AIyouPanel`, explicit text color, zero tonal/shadow elevation |

`AIyouPanel` is already used by the production common card wrapper. Existing public business component APIs are preserved. The capybara uses the existing original geometric drawing with a small static pale-blue halo, without adding animation or a success cue. No downloaded artwork is used.

`Modifier.aiyouFocusOutline()` is an optional primitive for future custom controls. It observes actual focus and draws a 2 dp primary outline, without adding a focus/click target. It is shown only in a clearly labeled development specimen; business input controls retain their existing focus implementations. Device keyboard focus and TalkBack are not claimed as verified.

The development specimen has ordinary `@Preview` annotations but no new `@PreviewTest`. This preserves the existing full 394-image inventory and packaging checks. The bounded visual workflow reuses the two Shell, four LocalEmpty and four LocalSaveFailed Previews. Production pages contain no new synthetic data; existing failure Preview fixtures remain explicitly synthetic.

## Evidence and limitations

- PASS: `python3 -m unittest discover -s scripts/tests -v`: 168 tests, 0 failures/errors (26.084 s).
- PASS: `python3 -m unittest scripts.tests.test_design_tokens -v`: 5 tests, now including new semantic status surfaces.
- PASS: `python3 scripts/generate-tokens.py --check`, `python3 -m py_compile scripts/verify-ui-polish-previews.py`, `git diff --check`.
- Token contrast over all six light surfaces: primary text >=11.07:1; supporting >=5.16:1; primary >=5.72:1; success >=5.28:1; error >=5.15:1. Functional outline >=3.64:1. Exact values are in `evidence/ui-polish/contrast.json`. Static sRGB calculations do not prove device accessibility.
- Local native build: NOT RUN; this execution environment has JDK 21, no configured JDK 17, no Android SDK, no adb/emulator and no readable/writable KVM. No SDK agreements were accepted or SDK packages installed.
- The first exploratory workflow run 39746936271 was deliberately cancelled to preserve the existing global Preview inventory before continuing the corrected bounded run; it is not passing CI evidence.
- Baseline image provenance: successful run 39736846240 at the exact base. Before PNGs are unchanged extractions from `d01-visual-review-001`, checked against its 394-image manifest. Screenshot smoke references are unapproved initial renders, not approved goldens.

- PASS: [bounded native CI 39747398671](https://github.com/T1doo/AIyou/actions/runs/39747398671), 7m9s, code commit `fcf9553c971e72014cf6b44b6c4ac2ec6e1f9c99`.
- PASS: `scripts/gradle.sh :app:checkDebugAarMetadata :app:assembleDebug :core:domain:test :core:designsystem:testDebugUnitTest :app:lintDebug :core:designsystem:lintDebug`. Actual XML independently inspected: domain 71 tests, design system 7 tests; failures/errors/skips 0. Design-system lint: zero issues; app lint: zero errors/warnings and one existing Hint (`AutoboxingStateCreation`, unchanged MainActivity.kt:238), deliberately left to the functional owner.
- PASS: `:app:updateDebugScreenshotTest` and `:app:validateDebugScreenshotTest`, each filtered to `*ShellRender*`, `*LocalEmptyRender*`, `*LocalSaveFailedRender*`; `python3 scripts/verify-ui-polish-previews.py`. Actual XML: 10 tests, failures/errors/skips 0. All ten valid PNGs verified with dimensions and SHA-256.
- VISUAL REVIEW: all ten PNGs opened with `view_image`. White content panels, primary-blue selected navigation, stronger headings and capybara halo are visible. On a 360dp regular page and on the large page, failure text and retry remain visible together. At 320dp/200% and in the 800dp landscape viewport, lower content requires scrolling; static renders do not establish reachability of every action. In the 200% failure render the error heading is visible, while its body/retry are below the viewport. No full-page accessibility acceptance is claimed.
- Before/after screenshots: [before normal](evidence/ui-polish/before-empty-normal.png), [after normal](evidence/ui-polish/after-empty-normal.png), [before 200%](evidence/ui-polish/before-empty-200.png), [after 200%](evidence/ui-polish/after-empty-200.png), [failure normal](evidence/ui-polish/after-failure-normal.png), [failure 200%](evidence/ui-polish/after-failure-200.png), [landscape](evidence/ui-polish/after-empty-landscape.png). These are unchanged native Layoutlib output bytes, not mockups.
- [Complete targeted manifest](evidence/ui-polish/native-preview-manifest.json), [test/artifact/APK hashes](evidence/ui-polish/native-results.json), [CI artifact](https://github.com/T1doo/AIyou/actions/runs/39747398671/artifacts/12412466574). Debug APK SHA-256: `18845b4025a8cb7f9966409b26021e0fd1eae56cf94342a9a798da0860210dcd`.
- The final delivery commit adds evidence only; `core/designsystem` remains the exact tested tree `60b2779dcb78fe25ad7de07688b658591b13d534`.

## Integration

Cherry-pick this development branch onto the parent-selected functional baseline. No shared page restructuring is requested by this slice. Parent should run final unified-query entry pixel/interaction checks after integrating both independent branches. Remaining device checks: actual 200% scrolling to every action, keyboard focus traversal, TalkBack, IME/safe areas, touch targets on a device, reduced-motion behavior and performance. Full 394-image regression was not run by this bounded workflow.

If the parent chooses a compact landscape header or earlier error placement next, coordinate `app/src/main/java/com/t1doo/aiyou/records/LocalRecordsScreen.kt`, `app/src/main/java/com/t1doo/aiyou/ui/NotebookPageHeader.kt` and `app/src/main/java/com/t1doo/aiyou/ui/NotebookChrome.kt` before editing. None is modified here.
