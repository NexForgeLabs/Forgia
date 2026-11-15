#!/usr/bin/env python3
"""Find a shortest strong root path in a retained HotSpot HPROF, without following Reference.referent.

Read-only diagnostic; the dump is private runtime evidence. JNI/global and Java-stack roots remain explicit.
"""
import argparse
import json
import mmap
from pathlib import Path
import struct

SIZES = {2: None, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('heap')
    parser.add_argument('--target', default='net.minecraft.server.level.ServerLevel')
    parser.add_argument('--output', required=True)
    parser.add_argument('--max-depth', type=int, default=30)
    args = parser.parse_args()
    with open(args.heap, 'rb') as source:
        data = mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ)
        pos = data.find(b'\0') + 1
        width = struct.unpack_from('>I', data, pos)[0]
        sizes = dict(SIZES, **{})
        sizes[2] = width
        id_format = '>Q' if width == 8 else '>I'

        def ident(at):
            return struct.unpack_from(id_format, data, at)[0]

        def u4(at):
            return struct.unpack_from('>I', data, at)[0]

        def u2(at):
            return struct.unpack_from('>H', data, at)[0]

        strings, names, classes, roots = {}, {}, {}, {}
        instances, arrays = [], []
        pos += 12
        while pos < len(data):
            tag = data[pos]
            length = u4(pos + 5)
            start, end = pos + 9, pos + 9 + length
            if tag == 1:
                strings[ident(start)] = data[start + width:end].decode('utf-8', errors='replace')
            elif tag == 2:
                names[ident(start + 4)] = ident(start + 8 + width)
            elif tag in (0x0C, 0x1C):
                cursor = start
                while cursor < end:
                    item = data[cursor]
                    cursor += 1
                    if item == 0x20:
                        obj = ident(cursor)
                        cursor += width + 4
                        sup, loader = ident(cursor), ident(cursor + width)
                        cursor += 6 * width + 4
                        constants = u2(cursor)
                        cursor += 2
                        for _ in range(constants):
                            typ = data[cursor + 2]
                            cursor += 3 + sizes[typ]
                        statics = []
                        count = u2(cursor)
                        cursor += 2
                        for _ in range(count):
                            name, typ = ident(cursor), data[cursor + width]
                            cursor += width + 1
                            if typ == 2:
                                statics.append((name, ident(cursor)))
                            cursor += sizes[typ]
                        fields = []
                        count = u2(cursor)
                        cursor += 2
                        for _ in range(count):
                            fields.append((ident(cursor), data[cursor + width]))
                            cursor += width + 1
                        classes[obj] = (sup, loader, statics, fields)
                    elif item == 0x21:
                        obj, cls, size = ident(cursor), ident(cursor + width + 4), u4(cursor + 2 * width + 4)
                        cursor += 2 * width + 8
                        instances.append((obj, cls, cursor, size))
                        cursor += size
                    elif item == 0x22:
                        obj, count = ident(cursor), u4(cursor + width + 4)
                        cls = ident(cursor + width + 8)
                        cursor += 2 * width + 8
                        arrays.append((obj, cls, cursor, count))
                        cursor += count * width
                    elif item == 0x23:
                        count, typ = u4(cursor + width + 4), data[cursor + width + 8]
                        cursor += width + 9 + count * sizes[typ]
                    elif item in (0xFF, 0x05, 0x07, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x90):
                        roots[ident(cursor)] = {'kind': hex(item)}
                        cursor += width
                    elif item == 0x01:
                        roots[ident(cursor)] = {'kind': 'JNI_GLOBAL', 'handle': hex(ident(cursor + width))}
                        cursor += 2 * width
                    elif item in (0x02, 0x03, 0x08, 0x8E):
                        roots[ident(cursor)] = {'kind': hex(item), 'threadSerial': u4(cursor + width), 'frameOrTrace': u4(cursor + width + 4)}
                        cursor += width + 8
                    elif item in (0x04, 0x06):
                        roots[ident(cursor)] = {'kind': hex(item), 'threadSerial': u4(cursor + width)}
                        cursor += width + 4
                    elif item == 0xFE:
                        cursor += 4 + width
                    else:
                        raise ValueError(f'Unknown heap tag {item:x} at {cursor - 1:x}')
            pos = end

        def classname(cls):
            return strings.get(names.get(cls), hex(cls)).replace('/', '.')

        layouts = {}
        for cls in classes:
            offset, refs, current = 0, [], cls
            while current in classes and current:
                sup, _, _, fields = classes[current]
                for name, typ in fields:
                    if typ == 2 and not (classname(current) == 'java.lang.ref.Reference' and strings.get(name) == 'referent'):
                        refs.append((offset, classname(current) + '.' + strings.get(name, hex(name))))
                    offset += sizes[typ]
                current = sup
            layouts[cls] = refs
        targets = {obj for obj, cls, _, _ in instances if classname(cls) == args.target}
        if not targets:
            raise ValueError('Target class has no instances: ' + args.target)
        frontier = set(targets)
        seen = set(targets)
        parents = {}
        labels = {obj: classname(cls) for obj, cls, _, _ in instances}
        labels.update({obj: classname(cls) for obj, cls, _, _ in arrays})
        labels.update({obj: 'Class<' + classname(obj) + '>' for obj in classes})
        found = None
        populations = []
        for depth in range(args.max_depth + 1):
            rooted = frontier & roots.keys()
            populations.append({'depth': depth, 'frontier': len(frontier)})
            print(populations[-1], flush=True)
            if rooted:
                found = next(iter(rooted))
                break
            following = set()

            def add(owner, target, field):
                if target in frontier and owner not in seen and owner not in following:
                    following.add(owner)
                    parents[owner] = (target, field)

            for obj, cls, at, size in instances:
                if obj in seen:
                    continue
                for offset, field in layouts.get(cls, []):
                    if offset + width <= size:
                        add(obj, ident(at + offset), field)
            for obj, _, at, count in arrays:
                if obj in seen:
                    continue
                for index in range(count):
                    add(obj, ident(at + index * width), '[' + str(index) + ']')
            for obj, (sup, loader, statics, _) in classes.items():
                add(obj, loader, '<classloader>')
                add(obj, sup, '<superclass>')
                for field, target in statics:
                    add(obj, target, 'static ' + strings.get(field, hex(field)))
            seen.update(following)
            frontier = following
            if not frontier:
                break
        path = []
        if found is not None:
            current = found
            while True:
                row = {'objectId': hex(current), 'class': labels.get(current), 'root': roots.get(current)}
                path.append(row)
                if current in targets:
                    break
                current, row['edge'] = parents[current]
        result = {'heap': args.heap, 'target': args.target, 'targetInstances': [hex(t) for t in targets],
                  'weakReferentEdgesExcluded': True, 'strongRootPathFound': bool(path),
                  'path': path, 'search': populations, 'heapObjectsScanned': len(instances) + len(arrays)}
        Path(args.output).write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    main()
