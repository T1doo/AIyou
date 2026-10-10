"""Validate complete non-interlaced PNG integrity produced by Layoutlib."""
import struct
import zlib


def verified_dimensions(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("invalid PNG signature")
    cursor = 8
    header = None
    compressed = bytearray()
    ended = False
    while cursor < len(data):
        if len(data) - cursor < 12:
            raise ValueError("truncated PNG chunk")
        length = struct.unpack(">I", data[cursor:cursor + 4])[0]
        end = cursor + 12 + length
        if end > len(data):
            raise ValueError("truncated PNG payload")
        kind = data[cursor + 4:cursor + 8]
        payload = data[cursor + 8:cursor + 8 + length]
        crc = struct.unpack(">I", data[end - 4:end])[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != crc:
            raise ValueError("PNG CRC mismatch")
        if header is None and kind != b"IHDR":
            raise ValueError("IHDR must be first")
        if kind == b"IHDR":
            if header is not None or length != 13:
                raise ValueError("invalid IHDR")
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            if length or end != len(data):
                raise ValueError("invalid IEND or trailing data")
            ended = True
        cursor = end
    if header is None or not ended or not compressed:
        raise ValueError("PNG requires IHDR, IDAT and IEND")
    width, height, depth, color, compression, filtering, interlace = header
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color)
    allowed_depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
    if (not width or not height or width * height > 32_000_000 or channels is None
            or depth not in allowed_depths[color] or compression or filtering or interlace):
        raise ValueError("unsupported PNG header; expected bounded non-interlaced image")
    stride = (width * channels * depth + 7) // 8 + 1
    expected = stride * height
    decoder = zlib.decompressobj()
    try:
        pixels = decoder.decompress(bytes(compressed), expected + 1)
    except zlib.error as error:
        raise ValueError("invalid IDAT compression") from error
    if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail or len(pixels) != expected:
        raise ValueError("invalid decompressed scanline length")
    if any(pixels[row * stride] > 4 for row in range(height)):
        raise ValueError("invalid PNG row filter")
    return width, height
