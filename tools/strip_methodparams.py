#!/usr/bin/env python3
"""Remove MethodParameters attributes from .class files.
JDK 21 javac emits nameless MethodParameters entries for synthetic/mandated params
(inner-class ctors, enums) which crash older D8 builds. Runtime behaviour is unchanged."""
import struct, sys, pathlib

def u2(b, o): return struct.unpack_from('>H', b, o)[0]
def u4(b, o): return struct.unpack_from('>I', b, o)[0]

def strip(data):
    o = 8; n = u2(data, o); o += 2; utf = {}; i = 1
    while i < n:
        t = data[o]
        if t == 1:
            l = u2(data, o + 1); utf[i] = data[o + 3:o + 3 + l].decode('utf-8', 'replace'); o += 3 + l
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): o += 5
        elif t in (5, 6): o += 9; i += 1
        elif t in (7, 8, 16, 19, 20): o += 3
        elif t == 15: o += 4
        else: raise ValueError('cp tag %d' % t)
        i += 1
    out = bytearray(data[:o]); o2 = o
    out += data[o2:o2 + 6]; o2 += 6
    ic = u2(data, o2); out += data[o2:o2 + 2 + 2 * ic]; o2 += 2 + 2 * ic

    def attrs(o2, drop):
        cnt = u2(data, o2); o2 += 2; keep = []
        for _ in range(cnt):
            name = utf.get(u2(data, o2)); l = u4(data, o2 + 2)
            chunk = data[o2:o2 + 6 + l]; o2 += 6 + l
            if name not in drop: keep.append(chunk)
        return o2, struct.pack('>H', len(keep)) + b''.join(keep)

    for _ in range(2):  # fields, methods
        cnt = u2(data, o2); out += data[o2:o2 + 2]; o2 += 2
        for _ in range(cnt):
            out += data[o2:o2 + 6]; o2 += 6
            o2, a = attrs(o2, {'MethodParameters'}); out += a
    o2, a = attrs(o2, set()); out += a
    return bytes(out)

for p in pathlib.Path(sys.argv[1]).rglob('*.class'):
    p.write_bytes(strip(p.read_bytes()))
