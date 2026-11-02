#!/usr/bin/env python3
"""Prepare the proposed bounded evidence only. Does not upload or admit a CI run."""
import argparse
import json
import hashlib
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--previews-only', action='store_true')
args = parser.parse_args()
target = ROOT / 'build/frontend-day-minimal.zip'
# A failed collection can never leave a previous archive eligible for upload.
target.unlink(missing_ok=True)
output = ROOT / 'build/frontend-day/after'
output.mkdir(parents=True, exist_ok=True)
previews = sorted((ROOT / 'app/src/screenshotTestDebug/reference').rglob('FrontendDay*.png'))
if len(previews) > 6:
    raise SystemExit('Refuse more than six current frontend frames')
for stale in output.glob('FrontendDay*.png'):
    stale.unlink()
for p in previews:
    frame_target = output / p.name
    frame_target.write_bytes(p.read_bytes())
if args.previews_only:
    raise SystemExit(0)
files = sorted(output.glob('FrontendDay*.png'))
for pattern in ('app/build/test-results/testDebugUnitTest/TEST-com.t1doo.aiyou.UiPreferencesTest.xml',
                'app/build/test-results/testDebugUnitTest/TEST-com.t1doo.aiyou.MixedCapturePresentationTest.xml',
                'app/build/test-results/updateDebugScreenshotTest/TEST-com.t1doo.aiyou.FrontendDayPreviewsKt.xml',
                'core/domain/build/test-results/test/TEST-*.xml'):
    files.extend(sorted(ROOT.glob(pattern)))
for suite in ('records.CaptureEditorSessionTest', 'records.MixedCaptureControllerTest',
              'data.AudioFilterSqlTest', 'data.CurrentImageGridControllerTest',
              'data.TaskUserReceiptSqlTest', 'records.ManualTaskViewModelTest',
              'data.SourceTaskSqlTest', 'data.TaskUndoFullBackupTest', 'data.LocalSearchSqlTest'):
    files.extend(sorted(ROOT.glob('checks/ai-protocol/remote-text/build/test-results/test/TEST-com.t1doo.aiyou.' + suite + '.xml')))
# Deliberately exclude APKs, baselines, entire report trees, caches, and arbitrary local evidence.
if not files or len(files) > 31 or sum(p.stat().st_size for p in files) > 6 * 1024 * 1024:
    raise SystemExit('Evidence exceeds proposed 6 MiB input / 32-file bound; do not upload')
with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as z:
    for p in files:
        z.write(p, str(p.relative_to(ROOT)))
    z.writestr('scope.json', json.dumps({'kind': 'synthetic Compose Preview and targeted XML',
        'device': False, 'baseline_included': False, 'apk_included': False,
        'proposed_retention_days': 1, 'budget_approval': 'required; workflow remains frozen',
        'source_sha': os.environ.get('GITHUB_SHA', 'local-uncommitted'),
        'files': [{'path': str(p.relative_to(ROOT)), 'bytes': p.stat().st_size,
                   'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]}))
if target.stat().st_size > 7 * 1024 * 1024:
    target.unlink()
    raise SystemExit('Archive exceeds proposed 7 MiB file bound; do not upload')
print('Prepared bounded local evidence; no upload performed')
