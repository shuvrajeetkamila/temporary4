#!/usr/bin/env python3
"""
Binary container helpers for the packaged Mumbai world layers (.mwl = Mumbai World Layer).

Every layer file is:
    magic  : 4 bytes  "MWL1"
    layer  : u32      layer id
    version: u32      format version
    payload: layer specific, little endian

Strings are u16 byte-length + UTF-8.  Large rasters are zlib compressed with an explicit
uncompressed-size field so the Java reader can allocate exactly once.  The manifest stores a
CRC-32 for every layer so a corrupt optional layer can be detected and reported rather than
silently rendering a hole in the city.
"""

import struct
import zlib

MAGIC = b"MWL1"
FORMAT_VERSION = 1

LAYER_MANIFEST = 0
LAYER_TERRAIN = 1
LAYER_NETWORK = 2
LAYER_CITY = 3
LAYER_CHUNKS = 4
LAYER_TOOLCHAIN = 9


class Writer:
    def __init__(self, layer_id):
        self.buf = bytearray()
        self.buf += MAGIC
        self.u32(layer_id)
        self.u32(FORMAT_VERSION)

    # --- scalars ---------------------------------------------------------------
    def u8(self, v):
        self.buf += struct.pack("<B", int(v) & 0xFF)

    def i8(self, v):
        self.buf += struct.pack("<b", max(-128, min(127, int(v))))

    def u16(self, v):
        self.buf += struct.pack("<H", int(v) & 0xFFFF)

    def i16(self, v):
        self.buf += struct.pack("<h", max(-32768, min(32767, int(v))))

    def u32(self, v):
        self.buf += struct.pack("<I", int(v) & 0xFFFFFFFF)

    def i32(self, v):
        self.buf += struct.pack("<i", int(v))

    def f32(self, v):
        self.buf += struct.pack("<f", float(v))

    def f64(self, v):
        self.buf += struct.pack("<d", float(v))

    def string(self, s):
        data = (s or "").encode("utf-8")
        if len(data) > 65535:
            data = data[:65535]
        self.u16(len(data))
        self.buf += data

    # --- blocks ----------------------------------------------------------------
    def blob(self, raw: bytes, compress=True):
        """u8 compressed flag, u32 raw size, u32 stored size, bytes."""
        if compress:
            packed = zlib.compress(raw, 9)
            if len(packed) < len(raw):
                self.u8(1)
                self.u32(len(raw))
                self.u32(len(packed))
                self.buf += packed
                return
        self.u8(0)
        self.u32(len(raw))
        self.u32(len(raw))
        self.buf += raw

    def bytes_raw(self, raw: bytes):
        self.buf += raw

    def data(self):
        return bytes(self.buf)

    def crc(self):
        return zlib.crc32(bytes(self.buf)) & 0xFFFFFFFF

    def write(self, path):
        with open(path, "wb") as f:
            f.write(self.data())
        return len(self.buf)


class Reader:
    """Mirror of Writer, used by the Python verifier (the Java runtime has its own)."""

    def __init__(self, path):
        with open(path, "rb") as f:
            self.buf = f.read()
        self.pos = 0
        magic = self.raw(4)
        if magic != MAGIC:
            raise ValueError(f"{path}: bad magic {magic!r}")
        self.layer = self.u32()
        self.version = self.u32()

    def raw(self, n):
        v = self.buf[self.pos:self.pos + n]
        self.pos += n
        return v

    def u8(self):
        return struct.unpack_from("<B", self.buf, self._adv(1))[0]

    def i8(self):
        return struct.unpack_from("<b", self.buf, self._adv(1))[0]

    def u16(self):
        return struct.unpack_from("<H", self.buf, self._adv(2))[0]

    def i16(self):
        return struct.unpack_from("<h", self.buf, self._adv(2))[0]

    def u32(self):
        return struct.unpack_from("<I", self.buf, self._adv(4))[0]

    def i32(self):
        return struct.unpack_from("<i", self.buf, self._adv(4))[0]

    def f32(self):
        return struct.unpack_from("<f", self.buf, self._adv(4))[0]

    def f64(self):
        return struct.unpack_from("<d", self.buf, self._adv(8))[0]

    def string(self):
        n = self.u16()
        return self.raw(n).decode("utf-8")

    def blob(self):
        compressed = self.u8()
        raw_size = self.u32()
        stored = self.u32()
        data = self.raw(stored)
        if compressed:
            data = zlib.decompress(data)
        if len(data) != raw_size:
            raise ValueError("blob size mismatch")
        return data

    def _adv(self, n):
        p = self.pos
        self.pos += n
        return p

    def file_crc(self):
        return zlib.crc32(self.buf) & 0xFFFFFFFF
