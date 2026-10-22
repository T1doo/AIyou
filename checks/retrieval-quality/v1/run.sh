#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
export AIYOU_QUALITY_ROOT="$task_root"
export AIYOU_QUALITY_OUTPUT="${1:-$task_root/.local-evidence/retrieval-quality-v1}"
mkdir -p "$AIYOU_QUALITY_OUTPUT"
if [[ -e "$AIYOU_QUALITY_OUTPUT/observations.json" ]]; then
    echo 'Existing baseline retained. Choose a new output directory for a justified comparison.' >&2
    exit 2
fi
cd "$task_root"
git diff --quiet 6cc68b6a3fbdab4f92d20cb30f5553363b300cef -- core app/src/main
(cd checks/retrieval-quality/v1 && sha256sum --check catalog.sha256)
scripts/gradle.sh --offline -p checks/ai-protocol -I "$task_root/checks/retrieval-quality/v1/run.init.gradle" \
    :remote-text:test --tests com.t1doo.aiyou.data.RetrievalQualityBaselineEvaluation \
    > "$AIYOU_QUALITY_OUTPUT/gradle.log" 2>&1
python3 checks/retrieval-quality/v1/score.py "$AIYOU_QUALITY_OUTPUT"
