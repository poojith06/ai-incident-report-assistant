#!/usr/bin/env python3
"""Reads GGUF metadata from the first bytes of a (partial) .gguf file; stdlib only.

Used to read `tokenizer.chat_template` of the Gemma 4 GGUF without loading the model
(see doc/PROMPTS.md "Gemma chat format").

Usage:
  adb -s 1d9c8f40 exec-out "head -c 60000000 /data/local/tmp/models/gemma/gemma-4-E2B_q4_0-it.gguf" > build/models/gemma_head.bin
  python tools/gguf_meta.py build/models/gemma_head.bin                 # list keys (arrays summarised)
  python tools/gguf_meta.py build/models/gemma_head.bin --key tokenizer.chat_template   # print one value
"""
import argparse
import struct
import sys

T_UINT8, T_INT8, T_UINT16, T_INT16, T_UINT32, T_INT32, T_FLOAT32, T_BOOL, T_STRING, T_ARRAY, T_UINT64, T_INT64, T_FLOAT64 = range(13)
SCALARS = {T_UINT8: "<B", T_INT8: "<b", T_UINT16: "<H", T_INT16: "<h", T_UINT32: "<I", T_INT32: "<i",
           T_FLOAT32: "<f", T_BOOL: "<?", T_UINT64: "<Q", T_INT64: "<q", T_FLOAT64: "<d"}


class Reader:
    def __init__(self, data):
        self.d = data
        self.p = 0

    def take(self, n):
        if self.p + n > len(self.d):
            raise EOFError("file prefix too short: pull more bytes")
        b = self.d[self.p:self.p + n]
        self.p += n
        return b

    def unpack(self, fmt):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self):
        return self.take(self.unpack("<Q")).decode("utf-8", errors="replace")

    def value(self, t):
        if t in SCALARS:
            return self.unpack(SCALARS[t])
        if t == T_STRING:
            return self.string()
        if t == T_ARRAY:
            et = self.unpack("<I")
            n = self.unpack("<Q")
            if et in SCALARS:
                self.take(struct.calcsize(SCALARS[et]) * n)
                return f"<array of {n} x type {et}>"
            items = [self.value(et) for _ in range(n)]
            return items if n <= 8 else f"<array of {n} x string>"
        raise ValueError(f"unknown gguf type {t}")


def read_meta(data):
    r = Reader(data)
    if r.take(4) != b"GGUF":
        raise ValueError("not a GGUF file")
    version = r.unpack("<I")
    r.unpack("<Q")  # tensor count
    n_kv = r.unpack("<Q")
    out = {"__version__": version}
    for _ in range(n_kv):
        key = r.string()
        out[key] = r.value(r.unpack("<I"))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--key")
    a = ap.parse_args()
    with open(a.file, "rb") as f:
        meta = read_meta(f.read())
    if a.key:
        sys.stdout.reconfigure(encoding="utf-8")
        print(meta[a.key])
    else:
        for k, v in meta.items():
            s = str(v)
            print(f"{k} = {s if len(s) < 200 else s[:200] + '...'}")


if __name__ == "__main__":
    main()
