"""Bounded ELF evidence parsing; static checks never certify Android runtime."""
from dataclasses import asdict, dataclass
import struct

PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2
SHT_NOBITS = 8
SHF_WRITE_ALLOC = 3
PAGE = 16384


class ElfError(ValueError):
    """Malformed or explicitly unsupported evidence; never a compatibility PASS."""


@dataclass(frozen=True)
class Segment:
    index: int
    header_offset: int
    raw_hex: str
    type: int
    flags: int
    offset: int
    vaddr: int
    paddr: int
    filesz: int
    memsz: int
    align: int


@dataclass(frozen=True)
class Section:
    index: int
    name: str | None
    type: int
    flags: int
    addr: int
    offset: int
    size: int


@dataclass(frozen=True)
class Elf:
    bits: int
    endian: str
    machine: int
    file_type: int
    program_headers: tuple[Segment, ...]
    sections: tuple[Section, ...]


def _file_span(data, offset, size, label):
    # Zero-file-size LOADs and SHT_NOBITS need no file bytes.
    if size and (offset > len(data) or size > len(data) - offset):
        raise ElfError(label + ": file span outside input")


def _address_span(address, size, bits, label):
    if address + size > (1 << bits) - 1:
        raise ElfError(label + ": address endpoint overflows ELF word")


def _table(data, offset, count, stride, expected, minimum, label):
    if stride != expected or offset < minimum:
        raise ElfError(label + ": invalid entry size or table offset")
    _file_span(data, offset, count * stride, label)


def parse_elf(data: bytes) -> Elf:
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ElfError("missing complete ELF identification")
    if data[4] not in (1, 2) or data[5] not in (1, 2) or data[6] != 1:
        raise ElfError("unsupported ELF class, byte order or identification version")
    bits = 64 if data[4] == 2 else 32
    endian = "little" if data[5] == 1 else "big"
    order = "<" if endian == "little" else ">"
    header_size, ph_size, sh_size = (64, 56, 64) if bits == 64 else (52, 32, 40)
    if len(data) < header_size:
        raise ElfError("truncated ELF header")
    h = struct.unpack_from(order + ("HHIQQQIHHHHHH" if bits == 64 else "HHIIIIIHHHHHH"), data, 16)
    typ, machine, version, _, phoff, shoff, _, ehsize, phent, phnum, shent, shnum, shstr = h
    if version != 1 or ehsize != header_size:
        raise ElfError("invalid ELF header version or size")
    # Extended counts require additional section-zero semantics; refuse explicitly.
    if phnum == 0xFFFF or shstr == 0xFFFF or (shnum == 0 and shoff != 0):
        raise ElfError("extended ELF numbering unsupported by bounded SQLCipher audit")
    if not phnum or phnum > 4096:
        raise ElfError("require 1..4096 program headers")
    _table(data, phoff, phnum, phent, ph_size, header_size, "program headers")
    segments, seen = [], set()
    for i in range(phnum):
        pos = phoff + i * phent
        v = struct.unpack_from(order + ("IIQQQQQQ" if bits == 64 else "IIIIIIII"), data, pos)
        if bits == 64:
            pt, flags, off, addr, phys, filesz, memsz, align = v
        else:
            pt, off, addr, phys, filesz, memsz, flags, align = v
        _file_span(data, off, filesz, "segment")
        _address_span(addr, memsz, bits, "segment")
        if pt == PT_LOAD:
            if filesz > memsz:
                raise ElfError("PT_LOAD FileSiz exceeds MemSiz")
            if align not in (0, 1) and (align & (align - 1) or (addr - off) % align):
                raise ElfError("PT_LOAD invalid alignment or address/offset congruence")
        if pt in (PT_LOAD, PT_GNU_RELRO):
            identity = (pt, flags, off, addr, phys, filesz, memsz, align)
            if identity in seen:
                raise ElfError("duplicate LOAD/RELRO program header")
            seen.add(identity)
        segments.append(Segment(i, pos, data[pos:pos + phent].hex(), pt, flags,
                                off, addr, phys, filesz, memsz, align))
    if not any(p.type == PT_LOAD for p in segments):
        raise ElfError("missing PT_LOAD")
    if sum(p.type == PT_GNU_RELRO for p in segments) > 1:
        raise ElfError("multiple GNU_RELRO headers are ambiguous for this audit")
    sections = []
    if shnum:
        _table(data, shoff, shnum, shent, sh_size, header_size, "section headers")
        if intersection((phoff, phoff + phnum * phent), (shoff, shoff + shnum * shent)):
            raise ElfError("program and section header tables overlap")
        if shstr >= shnum:
            raise ElfError("section-name table index outside section headers")
        values = [struct.unpack_from(order + ("IIQQQQIIQQ" if bits == 64 else "IIIIIIIIII"),
                                     data, shoff + i * shent) for i in range(shnum)]
        names = None
        if shstr:
            strings = values[shstr]
            if strings[1] != 3:
                raise ElfError("section-name table is not STRTAB")
            _file_span(data, strings[4], strings[5], "section-name table")
            names = data[strings[4]:strings[4] + strings[5]]
        for i, sec in enumerate(values):
            nameoff, st, flags, addr, off, size = sec[:6]
            if st not in (0, SHT_NOBITS):
                _file_span(data, off, size, "section")
            if flags & 2:
                _address_span(addr, size, bits, "allocated section")
            name = None
            if names is not None:
                end = names.find(b"\0", nameoff) if nameoff < len(names) else -1
                if end < 0:
                    raise ElfError("section name outside or unterminated in STRTAB")
                name = names[nameoff:end].decode("utf-8", errors="backslashreplace")
            sections.append(Section(i, name, st, flags, addr, off, size))
    elif shstr:
        raise ElfError("section-name index without section headers")
    return Elf(bits, endian, machine, typ, tuple(segments), tuple(sections))


def intersection(a, b):
    start, end = max(a[0], b[0]), min(a[1], b[1])
    return (start, end) if start < end else None


def subtract(interval, excluded):
    overlap = intersection(interval, excluded)
    if overlap is None:
        return [interval] if interval[0] < interval[1] else []
    return [(a, b) for a, b in ((interval[0], overlap[0]), (overlap[1], interval[1])) if a < b]


def page_protection(start, memsz, page):
    if start < 0 or memsz < 0:
        raise ElfError("memory range must be nonnegative")
    if page <= 0 or page & (page - 1):
        raise ElfError("page size must be positive power of two")
    # Explicit floor(start)..ceil(start+MemSiz), including endpoint boundaries.
    return (start // page * page, (start + memsz + page - 1) // page * page)


def layout_checks(elf: Elf, page=PAGE):
    page_protection(0, 0, page)
    loads = [p for p in elf.program_headers if p.type == PT_LOAD]
    relros = [p for p in elf.program_headers if p.type == PT_GNU_RELRO]
    load_checks = [{"index": p.index, "p_align_at_least_page": p.align >= page,
                    "page_offset_congruence": (p.vaddr - p.offset) % page == 0}
                   for p in loads]
    result = {"page": page, "load_checks": load_checks,
              "load_alignment": "PASS" if all(c["p_align_at_least_page"] and c["page_offset_congruence"]
                                              for c in load_checks) else "FAIL",
              "android_doc_relro_endpoint": "NOT_APPLICABLE" if not relros else "FAIL",
              "runtime": "NOT RUN", "section_headers_available": bool(elf.sections)}
    if not relros:
        return result
    rr = relros[0]
    raw = (rr.vaddr, rr.vaddr + rr.memsz)
    protected = page_protection(rr.vaddr, rr.memsz, page)
    result.update({"relro_raw_vaddr_memsz_range": raw, "relro_raw_end": raw[1],
                   "relro_end_mod_page": raw[1] % page, "expanded_protection_range": protected,
                   "android_doc_relro_endpoint": "PASS" if raw[1] % page == 0 else "FAIL"})
    coarse, fine = [], []
    for load in loads:
        if load.flags & PF_W:
            for outside in subtract((load.vaddr, load.vaddr + load.memsz), raw):
                hit = intersection(outside, protected)
                if hit:
                    coarse.append({"load_index": load.index, "outside_raw_relro": outside, "overlap": hit})
    for section in elf.sections:
        if section.flags & SHF_WRITE_ALLOC == SHF_WRITE_ALLOC:
            for outside in subtract((section.addr, section.addr + section.size), raw):
                hit = intersection(outside, protected)
                if hit:
                    fine.append({"section_index": section.index, "section_name": section.name,
                                 "outside_raw_relro": outside, "overlap": hit})
    gaps = [protected]
    # Gaps are unoccupied by *any* ALLOC section, not just non-writable names.
    for section in elf.sections:
        if section.flags & 2:
            gaps = [part for gap in gaps for part in subtract(gap, (section.addr, section.addr + section.size))]
    gaps = [part for gap in gaps for part in subtract(gap, raw)]
    uncovered_raw = [raw] if rr.memsz else []
    uncovered_mapped = list(uncovered_raw)
    for load in loads:
        uncovered_raw = [part for span in uncovered_raw
                         for part in subtract(span, (load.vaddr, load.vaddr + load.memsz))]
        if load.memsz:
            uncovered_mapped = [part for span in uncovered_mapped
                                for part in subtract(span, page_protection(load.vaddr, load.memsz, page))]
    result.update({"nonrelro_writable_load_overlaps": coarse,
                   "nonrelro_alloc_write_section_overlaps": fine if elf.sections else None,
                   "expanded_nonrelro_gaps_without_alloc_sections": gaps if elf.sections else None,
                   "relro_bytes_outside_raw_load_ranges": uncovered_raw,
                   "relro_bytes_outside_page_expanded_load_ranges": uncovered_mapped,
                   "overlap_interpretation": "Static ranges only: LOAD potential coverage includes padding; "
                   "section coverage is separate. Zero overlaps do not override Android doc FAIL or prove runtime safety."})
    return result


def selected_headers(elf):
    return [{key: value for key, value in asdict(p).items()
             if key not in ("header_offset", "raw_hex", "index")}
            for p in elf.program_headers if p.type in (PT_LOAD, PT_GNU_RELRO)]


def readelf_selected_headers(text):
    """Independent text oracle; consumes LLVM/GNU readelf -W -l output, not ELF bytes."""
    result = []
    for line in text.splitlines():
        cells = line.split()
        if not cells or cells[0] not in ("LOAD", "GNU_RELRO"):
            continue
        if len(cells) < 7:
            raise ElfError("truncated readelf LOAD/RELRO row")
        try:
            for n in cells[1:6] + cells[-1:]:
                if not n.startswith("0x") or not n[2:] or any(c not in "0123456789abcdefABCDEF" for c in n[2:]):
                    raise ValueError("require readelf hexadecimal token")
            numbers = [int(n, 16) for n in cells[1:6]]
            align = int(cells[-1], 16)
        except ValueError as e:
            raise ElfError("invalid readelf number") from e
        letters = "".join(cells[6:-1])
        if any(c not in "RWE" for c in letters) or len(set(letters)) != len(letters):
            raise ElfError("invalid readelf flag columns")
        off, addr, phys, filesz, memsz = numbers
        result.append({"type": PT_LOAD if cells[0] == "LOAD" else PT_GNU_RELRO,
                       "flags": sum({"R": 4, "W": 2, "E": 1}[c] for c in letters),
                       "offset": off, "vaddr": addr, "paddr": phys,
                       "filesz": filesz, "memsz": memsz, "align": align})
    if not result or not any(p["type"] == PT_LOAD for p in result):
        raise ElfError("readelf output has no LOAD evidence")
    return result
