#!/usr/bin/env python3
"""Generate the sole Compose palette from reviewable JSON; --check never writes."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "core/designsystem/src/main/kotlin/com/t1doo/aiyou/designsystem/AIyouTokens.kt"


def generate(tokens):
    lines = [
        "// Generated from design/tokens.json by scripts/generate-tokens.py. Do not edit.",
        "package com.t1doo.aiyou.designsystem", "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.unit.dp", "import androidx.compose.ui.unit.sp", "",
        "object AIyouColors {",
    ]
    for name, value in tokens["colors"].items():
        lines.append(f"    val {name} = Color(0xFF{value[1:]})")
    lines += ["}", "", "object AIyouDimensions {"]
    for name, value in tokens["dimensionsDp"].items():
        lines.append(f"    val {name} = {value}.dp")
    lines += ["}", "", "object AIyouTypeScale {"]
    for name, value in tokens["typographySp"].items():
        lines += [f'    val {name}Size = {value["size"]}.sp',
                  f'    val {name}LineHeight = {value["lineHeight"]}.sp']
    return "\n".join(lines + ["}", ""])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = generate(json.loads((ROOT / "design/tokens.json").read_text()))
    if args.check:
        if not TARGET.exists() or TARGET.read_text() != result:
            raise SystemExit("FAIL: generated Compose tokens differ; run scripts/generate-tokens.py")
        print("PASS: Compose tokens match JSON")
    else:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(result)


if __name__ == "__main__":
    main()
