#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
export AIYOU_QUALITY_ROOT="$task_root"
export AIYOU_QUALITY_STAGE="${1:?calibration, proposals or paired}"
export AIYOU_QUALITY_OUTPUT="${2:?new evidence directory required}"
case "$AIYOU_QUALITY_STAGE" in
 calibration) method=calibrateRealControllersAndSqlBeforeHoldout ;;
 proposals) method=emitActualProposalsWithoutSearchingHoldout ;;
 paired) method=runFrozenIndependentPairedPathsOnce ;;
 *) exit 2 ;;
esac
mkdir -p "$AIYOU_QUALITY_OUTPUT"
if [[ -e "$AIYOU_QUALITY_OUTPUT/gradle.log" ]]; then
 echo 'Existing execution preserved; a fresh directory and explicit reason are required.' >&2
 exit 2
fi
cd "$task_root"
git diff --quiet 50e31ae31d4bfd5ab66e4602ed118612994c03f9 -- core app/src/main
scripts/gradle.sh --offline -p checks/ai-protocol -I "$task_root/checks/retrieval-quality/v3/run.init.gradle" \
 :remote-text:test --tests "com.t1doo.aiyou.data.IndependentHoldoutEvaluation.$method" \
 > "$AIYOU_QUALITY_OUTPUT/gradle.log" 2>&1
