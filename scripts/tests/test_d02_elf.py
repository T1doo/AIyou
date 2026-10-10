import struct
import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from d02_elf import (ElfError, PT_LOAD, PT_GNU_RELRO, layout_checks, parse_elf,
                     page_protection, readelf_selected_headers, selected_headers)


def fixture(bits=64, endian="<", segments=None, sections=None):
    """Independent synthetic ELF encoder: flags stored in the class-specific position."""
    phsize, shsize, ehsize = (56, 64, 64) if bits == 64 else (32, 40, 52)
    segs = segments or [(PT_LOAD, 6, 0, 0, 0, 1024, 0x10000, 0x4000)]
    data = bytearray(1024)
    ident = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1 if endian == "<" else 2, 1]) + bytes(9)
    data[:16] = ident
    shoff = 768 if sections else 0
    struct.pack_into(endian + ("HHIQQQIHHHHHH" if bits == 64 else "HHIIIIIHHHHHH"), data, 16,
                     3, 183 if bits == 64 else 40, 1, 0, ehsize, shoff, 0,
                     ehsize, phsize, len(segs), shsize if sections else 0,
                     len(sections) if sections else 0, 0)
    for i, p in enumerate(segs):
        t, fl, off, va, pa, fs, ms, al = p
        fields = p if bits == 64 else (t, off, va, pa, fs, ms, fl, al)
        struct.pack_into(endian + ("IIQQQQQQ" if bits == 64 else "IIIIIIII"),
                         data, ehsize + i * phsize, *fields)
    for i, (st, fl, va, off, size) in enumerate(sections or []):
        struct.pack_into(endian + ("IIQQQQIIQQ" if bits == 64 else "IIIIIIIIII"),
                         data, shoff + i * shsize, 0, st, fl, va, off, size, 0, 0, 1, 0)
    return bytes(data)


def relro_segments(start=0x1000, memsz=0x1000, filesz=32, offset=512, load_end=0x10000):
    return [(PT_LOAD, 6, 0, 0, 0, 1024, load_end, 0x4000),
            (PT_GNU_RELRO, 4, offset, start, start, filesz, memsz, 1)]


class ElfEvidenceTests(unittest.TestCase):
    def test_all_classes_and_endianness_decode_raw_headers(self):
        for bits in (32, 64):
            for order in ("<", ">"):
                with self.subTest(bits=bits, order=order):
                    e = parse_elf(fixture(bits, order, relro_segments()))
                    self.assertEqual(bits, e.bits)
                    self.assertEqual("little" if order == "<" else "big", e.endian)
                    self.assertEqual(512, e.program_headers[1].offset)
                    self.assertEqual(0x1000, e.program_headers[1].vaddr)
                    self.assertEqual(0x1000, e.program_headers[1].memsz)
                    self.assertEqual(32, e.program_headers[1].filesz)
                    self.assertEqual(56 if bits == 64 else 32, len(bytes.fromhex(e.program_headers[1].raw_hex)))

    def test_identification_and_truncated_headers_fail(self):
        data = fixture()
        for cut in (0, 4, 15, 16, 51, 63, 64, 119):
            with self.subTest(cut=cut), self.assertRaises(ElfError):
                parse_elf(data[:cut])
        for position, value in ((0, 0), (4, 3), (5, 0), (6, 0)):
            bad = bytearray(data); bad[position] = value
            with self.subTest(position=position), self.assertRaises(ElfError):
                parse_elf(bytes(bad))

    def test_program_table_offset_and_size_bounds_fail(self):
        for off, stride, count in ((1024, 56, 1), (16, 56, 1), (64, 55, 1),
                                   (64, 56, 0), (64, 56, 4097), (64, 56, 0xFFFF)):
            bad = bytearray(fixture())
            struct.pack_into("<Q", bad, 32, off)
            struct.pack_into("<HH", bad, 54, stride, count)
            with self.subTest(off=off, stride=stride, count=count), self.assertRaises(ElfError):
                parse_elf(bytes(bad))

    def test_segment_file_span_and_virtual_endpoint_bounds_fail(self):
        for p in ((PT_LOAD, 6, 1020, 1020, 1020, 20, 20, 1),
                  (PT_LOAD, 6, 0, (1 << 64) - 2, 0, 1, 8, 1)):
            with self.subTest(p=p), self.assertRaises(ElfError):
                parse_elf(fixture(segments=[p]))

    def test_load_filesz_exceeds_memsz_is_invalid(self):
        with self.assertRaises(ElfError):
            parse_elf(fixture(segments=[(PT_LOAD, 6, 0, 0, 0, 100, 99, 1)]))

    def test_relro_filesz_can_exceed_memsz_formula_uses_memory(self):
        # The Android guide's aligned example explicitly has different FileSiz/MemSiz.
        e = parse_elf(fixture(segments=relro_segments(start=0x3FF0, memsz=16, filesz=32)))
        c = layout_checks(e)
        self.assertEqual("PASS", c["android_doc_relro_endpoint"])
        self.assertEqual(0x4000, c["relro_raw_end"])
        self.assertNotEqual(0, (e.program_headers[1].offset + e.program_headers[1].filesz) % 0x4000)
        self.assertNotEqual(0, (e.program_headers[1].vaddr + e.program_headers[1].filesz) % 0x4000)

    def test_load_alignment_and_congruence_are_separate_from_doc_relro(self):
        e = parse_elf(fixture(segments=[(PT_LOAD, 6, 0, 0, 0, 1024, 0x4000, 0x1000),
                                         (PT_GNU_RELRO, 4, 512, 0x3000, 0x3000, 16, 0x1000, 1)]))
        c = layout_checks(e)
        self.assertEqual("FAIL", c["load_alignment"])
        self.assertEqual("PASS", c["android_doc_relro_endpoint"])
        e = parse_elf(fixture(segments=[(PT_LOAD, 6, 0, 0x1000, 0, 1024, 0x1000, 0x1000)]))
        self.assertFalse(layout_checks(e)["load_checks"][0]["page_offset_congruence"])

    def test_invalid_load_alignment_or_native_congruence_fails(self):
        for align, addr in ((3, 0), (0x1000, 1)):
            with self.subTest(align=align, addr=addr), self.assertRaises(ElfError):
                parse_elf(fixture(segments=[(PT_LOAD, 6, 0, addr, 0, 1024, 1024, align)]))

    def test_duplicate_load_and_duplicate_relro_fail(self):
        base = relro_segments()
        for segs in ([base[0], base[0]], base + [base[1]],
                     base + [(PT_GNU_RELRO, 4, 600, 0x2000, 0x2000, 16, 16, 1)]):
            with self.subTest(segs=segs), self.assertRaises(ElfError):
                parse_elf(fixture(segments=segs))

    def test_relro_outside_load_memory_is_explicit_not_silently_safe(self):
        c = layout_checks(parse_elf(fixture(segments=relro_segments(start=0x10000, memsz=1))))
        self.assertEqual([(0x10000, 0x10001)], c["relro_bytes_outside_raw_load_ranges"])
        self.assertEqual([(0x10000, 0x10001)], c["relro_bytes_outside_page_expanded_load_ranges"])
        self.assertEqual("NOT RUN", c["runtime"])

    def test_relro_memory_in_load_page_padding_is_permitted_and_reported(self):
        # Actual NDK-linked binaries may pad RELRO MemSiz beyond the raw LOAD endpoint.
        c = layout_checks(parse_elf(fixture(segments=relro_segments(start=0x3000, memsz=0x1000,
                                                                  load_end=0x3F00))), 4096)
        self.assertEqual([(0x3F00, 0x4000)], c["relro_bytes_outside_raw_load_ranges"])
        self.assertEqual([], c["relro_bytes_outside_page_expanded_load_ranges"])

    def test_page_rounding_exact_and_unaligned_endpoints(self):
        self.assertEqual((0x4000, 0x8000), page_protection(0x4000, 0x4000, 0x4000))
        self.assertEqual((0, 0x8000), page_protection(0x3FF0, 0x20, 0x4000))
        self.assertEqual((0x4000, 0x4000), page_protection(0x4000, 0, 0x4000))
        for page in (0, -1, 3):
            with self.subTest(page=page), self.assertRaises(ElfError):
                layout_checks(parse_elf(fixture()), page)

    def test_round_down_start_detects_writable_overlap(self):
        e = parse_elf(fixture(segments=relro_segments(start=0x1100, memsz=0x2F00),
                             sections=[(8, 3, 0x1000, 99999, 0x80)]))
        c = layout_checks(e)
        self.assertEqual("PASS", c["android_doc_relro_endpoint"])
        self.assertEqual((0x1000, 0x1080), c["nonrelro_alloc_write_section_overlaps"][0]["overlap"])

    def test_round_up_end_detects_writable_overlap(self):
        e = parse_elf(fixture(segments=relro_segments(start=0, memsz=0x4100),
                             sections=[(8, 3, 0x4200, 99999, 0x100)]))
        c = layout_checks(e)
        self.assertEqual("FAIL", c["android_doc_relro_endpoint"])
        self.assertEqual((0x4200, 0x4300), c["nonrelro_alloc_write_section_overlaps"][0]["overlap"])

    def test_load_padding_gap_not_occupied_alloc_write_section(self):
        e = parse_elf(fixture(segments=relro_segments(start=0, memsz=0x4100),
                             sections=[(8, 3, 0x8000, 99999, 0x100)]))
        c = layout_checks(e)
        self.assertTrue(c["nonrelro_writable_load_overlaps"])
        self.assertEqual([], c["nonrelro_alloc_write_section_overlaps"])
        self.assertEqual([(0x4100, 0x8000)], c["expanded_nonrelro_gaps_without_alloc_sections"])
        self.assertEqual("FAIL", c["android_doc_relro_endpoint"])  # zero fine overlap never overrides doc formula

    def test_endpoint_failure_without_load_overlap_is_not_runtime_proof(self):
        e = parse_elf(fixture(segments=relro_segments(start=0, memsz=0x4100, load_end=0x4100),
                             sections=[(8, 3, 0, 99999, 0x4100)]))
        c = layout_checks(e)
        self.assertEqual("FAIL", c["android_doc_relro_endpoint"])
        self.assertEqual([], c["nonrelro_writable_load_overlaps"])
        self.assertEqual([], c["nonrelro_alloc_write_section_overlaps"])
        self.assertEqual("NOT RUN", c["runtime"])

    def test_touching_boundary_does_not_overlap(self):
        e = parse_elf(fixture(segments=relro_segments(start=0, memsz=0x4100),
                             sections=[(8, 3, 0x8000, 99999, 10)]))
        self.assertEqual([], layout_checks(e)["nonrelro_alloc_write_section_overlaps"])

    def test_sectionless_evidence_is_unknown_and_no_relro_is_not_app_pass(self):
        c = layout_checks(parse_elf(fixture(segments=relro_segments())))
        self.assertIsNone(c["nonrelro_alloc_write_section_overlaps"])
        self.assertFalse(c["section_headers_available"])
        c = layout_checks(parse_elf(fixture()))
        self.assertEqual("NOT_APPLICABLE", c["android_doc_relro_endpoint"])
        self.assertEqual("NOT RUN", c["runtime"])

    def test_bss_zero_filesz_is_not_truncated_file_evidence(self):
        e = parse_elf(fixture(segments=[(PT_LOAD, 6, 99999, 0, 0, 0, 4096, 1)]))
        self.assertEqual(0, e.program_headers[0].filesz)
        self.assertEqual(4096, e.program_headers[0].memsz)

    def test_zero_memory_load_does_not_fabricate_mapped_page_coverage(self):
        e = parse_elf(fixture(segments=[(PT_LOAD, 6, 0, 0x1100, 0, 0, 0, 1),
                                       (PT_GNU_RELRO, 4, 512, 0x1100, 0x1100, 16, 16, 1)]))
        self.assertEqual([(0x1100, 0x1110)],
                         layout_checks(e)["relro_bytes_outside_page_expanded_load_ranges"])

    def test_section_table_bounds_overlap_and_names_are_rejected(self):
        good = fixture(sections=[(8, 3, 0, 99999, 4096)])
        cases = []
        b = bytearray(good); struct.pack_into("<Q", b, 40, 1000); cases.append(b)
        b = bytearray(good); struct.pack_into("<Q", b, 40, 64); cases.append(b)
        b = bytearray(good); struct.pack_into("<H", b, 62, 1); cases.append(b)
        b = bytearray(good); struct.pack_into("<H", b, 58, 63); cases.append(b)
        b = bytearray(good); struct.pack_into("<I", b, 768 + 4, 1); struct.pack_into("<Q", b, 768 + 24, 1000); cases.append(b)
        for bad in cases:
            with self.subTest(bad=bytes(bad[:64])), self.assertRaises(ElfError):
                parse_elf(bytes(bad))

    def test_section_name_requires_bounded_terminated_strtab(self):
        good = bytearray(fixture(sections=[(0, 0, 0, 0, 0), (3, 0, 0, 700, 5)]))
        struct.pack_into("<H", good, 62, 1)
        good[700:705] = b"\0abc\0"
        self.assertEqual("", parse_elf(bytes(good)).sections[0].name)
        bad = bytearray(good); struct.pack_into("<I", bad, 768, 5)
        with self.assertRaises(ElfError): parse_elf(bytes(bad))
        bad = bytearray(good); bad[700:705] = b"abcde"
        with self.assertRaises(ElfError): parse_elf(bytes(bad))

    def test_readelf_oracle_preserves_field_flags_and_order(self):
        text = "  LOAD 0x000 0x1000 0x1000 0x80 0x800 R E 0x1000\n  GNU_RELRO 0x80 0x2000 0x2000 0x90 0x500 RW 0x1"
        result = readelf_selected_headers(text)
        self.assertEqual(5, result[0]["flags"])
        self.assertEqual(6, result[1]["flags"])
        self.assertEqual(0x90, result[1]["filesz"])
        self.assertEqual(0x500, result[1]["memsz"])
        self.assertEqual(PT_GNU_RELRO, result[1]["type"])

    def test_readelf_missing_truncated_and_bad_flags_are_rejected(self):
        for text in ("", "GNU_RELRO 0x0 0x0 0x0 0x0 0x0 R 0x1",
                     "LOAD 0x0 0x0", "LOAD 0x0 0x0 0x0 0x10 0x20 RX 0x1",
                     "LOAD 0x0 0x0 0x0 bad 0x20 R 0x1"):
            with self.subTest(text=text), self.assertRaises(ElfError):
                readelf_selected_headers(text)

    def test_selected_header_comparison_catches_memsz_mismatch(self):
        e = parse_elf(fixture(segments=relro_segments()))
        result = selected_headers(e)
        self.assertEqual(2, len(result))
        bad = [dict(x) for x in result]; bad[1]["memsz"] -= 1
        self.assertNotEqual(result, bad)

    def test_readelf_zero_flags_is_not_missing_evidence(self):
        row = readelf_selected_headers("LOAD 0x0 0x0 0x0 0x10 0x20 0x1")
        self.assertEqual(0, row[0]["flags"])


if __name__ == "__main__":
    unittest.main()
