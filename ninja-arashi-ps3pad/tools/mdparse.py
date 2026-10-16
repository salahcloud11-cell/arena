#!/usr/bin/env python3
"""Minimal IL2CPP global-metadata.dat v24 parser (read-only, names/types/methods)."""
import struct, sys, json

class Meta:
    def __init__(self, path):
        self.d = d = open(path, 'rb').read()
        h = struct.unpack_from('<40i', d, 0)
        self.sanity, self.version = h[0], h[1]
        # (offset, size/count) pairs, in v24 header order
        keys = ["stringLiteral","stringLiteralData","string","events","properties","methods",
                "paramDefaultValues","fieldDefaultValues","fieldAndParamDefaultValueData",
                "fieldMarshaledSizes","parameters","fields","genericParameters",
                "genericParameterConstraints","genericContainers","nestedTypes","interfaces",
                "vtableMethods","interfaceOffsets"]
        self.h = {}
        i = 2
        for k in keys:
            self.h[k] = (h[i], h[i+1]); i += 2
        # remaining header fields we care about (typeDefinitions, images, assemblies)
        # continue reading as int pairs from where we stopped
        rest = struct.unpack_from('<%di' % ((len(d) // 4)), d, 0)
        # find typeDefinitions offset/count by scanning known layout
        # v24 order after interfaceOffsets: typeDefinitions, rgctxEntries, images, assemblies, ...
        self.td = (rest[2 + 2*19], rest[2 + 2*19 + 1])
        self.rgctx = (rest[2 + 2*20], rest[2 + 2*20 + 1])
        self.images = (rest[2 + 2*21], rest[2 + 2*21 + 1])
        self.assemblies = (rest[2 + 2*22], rest[2 + 2*22 + 1])

    def cstr(self, off):
        e = self.d.index(b'\0', off)
        return self.d[off:e].decode('utf-8', 'replace')

    def s(self, idx):
        if idx < 0: return None
        return self.cstr(self.h["string"][0] + idx)

    def string_literal(self, idx):
        off, cnt = self.h["stringLiteral"]
        if idx >= cnt // 4: return None
        str_off = struct.unpack_from('<i', self.d, off + idx*4)[0]
        len_off = struct.unpack_from('<i', self.d, self.h["stringLiteralData"][0] + str_off)[0]
        data = self.h["stringLiteralData"][0] + str_off + 4
        return self.d[data:data+len_off].decode('utf-8', 'replace')

    def type_defs(self):
        off, cnt = self.td
        n = cnt // 100
        for i in range(n):
            b = off + i*100
            (nameIdx, nsIdx, byval, byref, declType, parent, elemType, rgctxStart, rgctxCnt,
             genericCont, flags, fieldStart, methodStart, eventStart, propStart, nestedStart,
             ifaceStart, vtableStart, ioffStart) = struct.unpack_from('<19i', self.d, b)
            (mc, pc, fc, ec, ntc, vc, ic, ioc) = struct.unpack_from('<8H', self.d, b + 76)
            token = struct.unpack_from('<I', self.d, b + 96)[0]
            yield dict(i=i, name=self.s(nameIdx), ns=self.s(nsIdx), flags=flags,
                       fieldStart=fieldStart, fieldCount=fc, methodStart=methodStart,
                       methodCount=mc, token=token, parent=parent)

    def methods(self):
        off, cnt = self.h["methods"]
        n = cnt // 56  # Il2CppMethodDefinition v24 size
        for i in range(n):
            b = off + i*56
            nameIdx, declType, retType, paramStart, genericCont, methodIdx, invokerIdx, token, flags, iflags, slot = struct.unpack_from('<5iIiIIHHH'.replace('i','i'), self.d, b)
            yield i, self.s(nameIdx)

    def fields(self):
        off, cnt = self.h["fields"]
        n = cnt // 12
        for i in range(n):
            b = off + i*12
            nameIdx, typeIdx, token = struct.unpack_from('<iiI', self.d, b)
            yield i, self.s(nameIdx)

if __name__ == '__main__':
    m = Meta('assets/bin/Data/Managed/Metadata/global-metadata.dat')
    print("version", m.version, "typeDefs", m.td, "images", m.images, "assemblies", m.assemblies)
    im_off, im_cnt = m.images
    n = im_cnt // 40
    print("images:", n)
    for i in range(n):
        b = im_off + i*40
        nameIdx, asmIdx, typStart, typCnt, expStart, expCnt, entryPoint, token = struct.unpack_from('<8i', m.d, b)
        print("   ", m.s(nameIdx), "types:", typCnt, "(start", typStart, ")")
