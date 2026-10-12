#!/usr/bin/env python3
"""
The packaged world container, "MWL2".

A deliberately boring format: a magic number, a directory of named layers, and
zlib-deflated payloads. It is written here and read in three independent
places — core/.../world/WorldFile.java inside the game, tools/verify_world.py
in CI, and tools/preview_world.py for eyeballing. Keeping the format dull is
what makes those three agree.

Everything is little-endian, which is what both Android and every CI runner
use natively.

  header   magic "MWL2" | u32 version | u32 layerCount | u32 flags
           u64 builtUnixSeconds | 32 bytes buildId
  directory  layerCount entries of:
           16 bytes name (NUL padded) | u32 kind | u32 crc32
           u64 rawBytes | u64 storedBytes | u64 offset
  payloads zlib streams at the recorded offsets
"""

import binascii
import io
import struct
import time
import zlib

MAGIC = b"MWL2"
VERSION = 2

KIND_RAW = 0
KIND_GRID_I16 = 1      # int16 grid, row-major, delta coded along x
KIND_GRID_U8 = 2       # uint8 grid, row-major
KIND_BITSET = 3
KIND_VECTOR = 4        # quantised vector features
KIND_TABLE = 5         # length-prefixed records
KIND_JSON = 6
KIND_INDEX = 7

# OR this into `kind` to store a layer verbatim instead of deflating it at the
# container level. Used for the chunk payload, whose entries are individually
# compressed so a streaming worker inflates one 30 kB tile rather than the
# whole hundred-megabyte layer.
KIND_STORED = 0x100

_DIR_ENTRY = struct.Struct("<16sIIQQQ")
_HEADER = struct.Struct("<4sIIIQ32s")


class Writer:
    def __init__(self, build_id=b""):
        self.layers = []                       # (name, kind, raw bytes)
        self.build_id = (build_id or b"")[:32].ljust(32, b"\0")

    def add(self, name, kind, data):
        if isinstance(data, bytearray):
            data = bytes(data)
        assert isinstance(data, bytes), name
        assert len(name.encode()) <= 16, name
        self.layers.append((name, kind, data))
        return len(data)

    def add_stored(self, name, kind, data):
        """Add a layer that is written verbatim, with no container deflate."""
        return self.add(name, kind | KIND_STORED, data)

    def add_grid_i16(self, name, arr, nx, nz):
        """Delta code each row, then store as little-endian int16."""
        import numpy as np
        a = np.asarray(arr, dtype=np.int16).reshape(nz, nx)
        d = np.empty_like(a)
        d[:, 0] = a[:, 0]
        d[:, 1:] = (a[:, 1:].astype(np.int32) - a[:, :-1].astype(np.int32)
                    ).astype(np.int16)
        return self.add(name, KIND_GRID_I16, d.tobytes(order="C"))

    def write(self, path, level=9):
        blobs = []
        for (_n, k, raw) in self.layers:
            blobs.append(raw if (k & KIND_STORED) else zlib.compress(raw, level))
        head = _HEADER.size + _DIR_ENTRY.size * len(self.layers)
        out = io.BytesIO()
        out.write(_HEADER.pack(MAGIC, VERSION, len(self.layers), 0,
                               int(time.time()), self.build_id))
        off = head
        for (name, kind, raw), c in zip(self.layers, blobs):
            out.write(_DIR_ENTRY.pack(name.encode().ljust(16, b"\0"), kind,
                                      binascii.crc32(raw) & 0xFFFFFFFF,
                                      len(raw), len(c), off))
            off += len(c)
        for c in blobs:
            out.write(c)
        data = out.getvalue()
        with open(path, "wb") as fh:
            fh.write(data)
        return len(data)

    def summary(self):
        rows = []
        for (name, kind, raw) in self.layers:
            c = len(raw) if (kind & KIND_STORED) else len(zlib.compress(raw, 9))
            rows.append({"layer": name, "kind": kind, "raw": len(raw), "stored": c,
                         "ratio": round(len(raw) / max(c, 1), 1)})
        return rows


class Reader:
    def __init__(self, path):
        with open(path, "rb") as fh:
            self.buf = fh.read()
        magic, ver, n, _flags, built, build_id = _HEADER.unpack_from(self.buf, 0)
        if magic != MAGIC:
            raise ValueError(f"{path}: not an MWL2 file (magic {magic!r})")
        if ver != VERSION:
            raise ValueError(f"{path}: version {ver}, expected {VERSION}")
        self.version = ver
        self.built = built
        self.build_id = build_id.rstrip(b"\0").decode("utf-8", "replace")
        self.dir = {}
        self.order = []
        o = _HEADER.size
        for _ in range(n):
            name, kind, crc, raw, stored, off = _DIR_ENTRY.unpack_from(self.buf, o)
            o += _DIR_ENTRY.size
            nm = name.rstrip(b"\0").decode()
            self.dir[nm] = (kind, crc, raw, stored, off)
            self.order.append(nm)

    def has(self, name):
        return name in self.dir

    def raw(self, name):
        kind, crc, raw, stored, off = self.dir[name]
        chunk = self.buf[off:off + stored]
        data = chunk if (kind & KIND_STORED) else zlib.decompress(chunk)
        if len(data) != raw:
            raise ValueError(f"{name}: expected {raw} bytes, got {len(data)}")
        if (binascii.crc32(data) & 0xFFFFFFFF) != crc:
            raise ValueError(f"{name}: CRC mismatch, the layer is corrupt")
        return data

    def grid_i16(self, name, nx, nz):
        import numpy as np
        d = np.frombuffer(self.raw(name), dtype="<i2").reshape(nz, nx).astype(np.int32)
        return np.cumsum(d, axis=1, dtype=np.int32).astype(np.int16)

    def kind(self, name):
        return self.dir[name][0]

    def slice_stored(self, name, offset, length):
        """Bytes straight out of a stored layer, with no inflate."""
        kind, _crc, _raw, stored, off = self.dir[name]
        if not (kind & KIND_STORED):
            raise ValueError(f"{name} is not a stored layer")
        if offset < 0 or offset + length > stored:
            raise ValueError(f"{name}: slice {offset}+{length} past {stored}")
        return self.buf[off + offset: off + offset + length]

    def stored_bytes(self, name):
        return self.dir[name][3]


# --------------------------------------------------------------------------
# Small helpers shared by the compiler and the verifier.
# --------------------------------------------------------------------------

def pack_u32(values):
    import numpy as np
    return np.asarray(values, dtype="<u4").tobytes()


def pack_i16(values):
    import numpy as np
    return np.asarray(values, dtype="<i2").tobytes()


def pack_strings(strings):
    """u32 count, then u32 offsets, then a UTF-8 blob."""
    blob = bytearray()
    offs = []
    for s in strings:
        offs.append(len(blob))
        blob += s.encode("utf-8")
    offs.append(len(blob))
    out = bytearray()
    out += struct.pack("<I", len(strings))
    import numpy as np
    out += np.asarray(offs, dtype="<u4").tobytes()
    out += blob
    return bytes(out)


def unpack_strings(data):
    (n,) = struct.unpack_from("<I", data, 0)
    import numpy as np
    offs = np.frombuffer(data, dtype="<u4", count=n + 1, offset=4)
    base = 4 + 4 * (n + 1)
    return [data[base + offs[i]: base + offs[i + 1]].decode("utf-8", "replace")
            for i in range(n)]
