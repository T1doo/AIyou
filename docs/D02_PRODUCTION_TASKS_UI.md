# Production task-route presentation gate

This slice starts from `d361b595dc3bc54d0858aa9ab8dac8f49b48442c` and fills the
missing whole production task-page preview. `LocalTasksRender` invokes the real
`AIyouApp` with `Destination.TASKS`, ready synthetic local-record state and ready
display preferences. It does not set `previewDemo`, fabricate a formal task,
or substitute a component preview for the production route.

The task page now explicitly says that manual creation and completion are not
open. Previously it only said that no task/reminder had been created, which did
not explain why no operation was available. The demo wording remains unchanged.

## Scope of verification

- Four real Compose render configurations: 360 × 800 dp, 320 × 1000 dp at 200%
  font size, 800 × 360 dp landscape, and 840 × 1000 dp wide.
- Three JVM Compose interaction tests use the real non-demo route. They verify
  that synthetic local records do not become formal tasks, unavailable actions
  are explicit, settings remains reachable, navigation returns to the selected
  task page, and the independent capture draft survives. The short portrait
  test uses 320 × 640 dp at 200% font size; landscape uses 800 × 360 dp.
- One existing settings-return test is selected alongside the three new tests.
- A short-viewport test must scroll the real LazyColumn to compose an off-screen
  item before asserting its visibility. The first attempt incorrectly searched
  for that uncomposed item directly; its failure is retained as test evidence,
  not evidence of a production scroll defect. The visibility assertion remains.

Current task persistence, creation, proposal confirmation and completion are
**not implemented**; the original plan assigns them to D07. Therefore populated
formal-task and completion-success states are **NOT RUN / unavailable**. An
empty route and a disabled demo proposal cannot satisfy those future tests.
This slice does not create a task backend, execute AI suggestions or schedule
notifications.

## Targeted commands and evidence boundary

Use the pinned wrapper and existing accepted SDK as documented in BUILD.md:

```sh
scripts/gradle.sh :app:testDebugUnitTest \
  --tests 'com.t1doo.aiyou.ProductionTasksPresentationTest' \
  --tests 'com.t1doo.aiyou.CompanionSettingsPresentationTest.eachProductionDestinationReturnsFromSettingsWithoutChangingSelection' \
  :app:lintDebug :app:assembleDebug \
  :app:updateDebugScreenshotTest --tests '*LocalTasksRender*' \
  :app:validateDebugScreenshotTest --tests '*LocalTasksRender*' --offline
python3 scripts/verify-preview-results.py --production-tasks-only
python3 -m unittest discover -s scripts/tests -p test_visual_delivery.py -v
```

The targeted preview guard writes
`app/build/reports/d02-production-tasks-preview-manifest.json`. It requires four
actual successful task-render cases and four PNGs and never rewrites the prior
full-render manifest. The global catalog now expects 390 renders and 95 app JVM
cases when a future full run is requested; these are requirements, not results
of this targeted run. Previous 386-render / 92-app-case evidence belongs to the
prior source and is preserved separately. The original 18 XML / 11 visually
reviewed PNG / 386-image manifest delivery retains its original identity.

Actual command results, image hashes, independent visual review and exact final
commit are recorded in the delivery report. Static rendering and JVM semantics
do not establish device/TalkBack/IME performance. API26 native first-open,
reopen and NIO/fileKey verification remain NOT RUN. No new runner, permission,
SDK license acceptance or Library upload is part of this slice.
