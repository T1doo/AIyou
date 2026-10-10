"""Regression: render guard must reject fake/truncated PNG evidence."""
import importlib.util
from pathlib import Path
import struct
import unittest
import zlib

spec = importlib.util.spec_from_file_location("png_integrity", Path(__file__).resolve().parents[1] / "png_integrity.py")
png = importlib.util.module_from_spec(spec)
spec.loader.exec_module(png)


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)


def image(pixels=b"\x00\x10\x20\x30"):
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


class PngIntegrityRegression(unittest.TestCase):
    def test_complete_image_and_split_data_chunks(self):
        self.assertEqual((1, 1), png.verified_dimensions(image()))
        compressed = zlib.compress(b"\x00\x10\x20\x30")
        split = image()[:33] + chunk(b"IDAT", compressed[:3]) + chunk(b"IDAT", compressed[3:]) + chunk(b"IEND", b"")
        self.assertEqual((1, 1), png.verified_dimensions(split))

    def test_original_24_byte_false_positive_is_rejected(self):
        fake = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 320, 800)
        with self.assertRaises(ValueError):
            png.verified_dimensions(fake)

    def test_truncation_crc_and_trailing_data_are_rejected(self):
        corrupted = bytearray(image())
        corrupted[29] ^= 1
        for invalid in (image()[:-1], image()[:33], bytes(corrupted), image() + b"trailing"):
            with self.subTest(data=invalid), self.assertRaises(ValueError):
                png.verified_dimensions(invalid)

    def test_invalid_compression_scanline_and_filter_are_rejected(self):
        for invalid in (image(b""), image(b"\x00\x10"), image(b"\x05\x10\x20\x30"),
                        image()[:33] + chunk(b"IDAT", b"bad stream") + chunk(b"IEND", b"")):
            with self.subTest(data=invalid), self.assertRaises(ValueError):
                png.verified_dimensions(invalid)


if __name__ == "__main__":
    unittest.main()
