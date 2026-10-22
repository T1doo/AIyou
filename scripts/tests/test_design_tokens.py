"""Design contracts: readability, control visibility, and usable type scale."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOKENS = json.loads((ROOT / "design/tokens.json").read_text())


def luminance(hex_color):
    channels = [int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return sum(c * weight for c, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast(foreground, background):
    light, dark = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (light + 0.05) / (dark + 0.05)


class DesignTokenContracts(unittest.TestCase):
    def test_contrast_calculator_known_black_white_reference(self):
        self.assertAlmostEqual(21, contrast("#000000", "#FFFFFF"))
        self.assertEqual(1, contrast("#70665C", "#70665C"))

    def test_small_text_is_readable_on_all_used_light_surfaces(self):
        colors = TOKENS["colors"]
        for foreground in ("textPrimary", "textSecondary", "primary", "success", "error"):
            for background in ("background", "surface", "surfaceMuted", "primaryContainer", "errorContainer", "successContainer"):
                with self.subTest(foreground=foreground, background=background):
                    self.assertGreaterEqual(contrast(colors[foreground], colors[background]), 4.5)

    def test_filled_action_text_and_functional_outlines_are_visible(self):
        colors = TOKENS["colors"]
        for background in ("primary", "success", "error"):
            with self.subTest(background=background):
                self.assertGreaterEqual(contrast(colors["onPrimary"], colors[background]), 4.5)
        for background in ("background", "surface", "surfaceMuted"):
            with self.subTest(background=background):
                self.assertGreaterEqual(contrast(colors["outlineFunctional"], colors[background]), 3)

    def test_touch_and_reading_metrics_preserve_plan_minimums(self):
        self.assertGreaterEqual(TOKENS["dimensionsDp"]["minTouchTarget"], 48)
        self.assertGreaterEqual(TOKENS["dimensionsDp"]["badgeSize"], 40)
        self.assertLessEqual(TOKENS["dimensionsDp"]["badgeSize"], 56)
        self.assertEqual(16, TOKENS["typographySp"]["body"]["size"])
        for style in TOKENS["typographySp"].values():
            self.assertGreater(style["lineHeight"], style["size"])

    def test_compose_palette_is_generated_from_reviewable_source(self):
        path = ROOT / "scripts/generate-tokens.py"
        spec = importlib.util.spec_from_file_location("token_generator", path)
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        self.assertEqual(generator.generate(TOKENS), generator.TARGET.read_text())


if __name__ == "__main__":
    unittest.main()
