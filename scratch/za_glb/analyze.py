import json, struct, sys, os, collections

def analyze(path):
    with open(path, 'rb') as f:
        data = f.read()
    magic, ver, total = struct.unpack_from('<III', data, 0)
    assert magic == 0x46546C67, "not a GLB"
    off = 12
    js = None
    bin_len = 0
    while off < len(data):
        clen, ctype = struct.unpack_from('<II', data, off)
        chunk = data[off+8:off+8+clen]
        if ctype == 0x4E4F534A:
            js = json.loads(chunk.decode('utf-8'))
        elif ctype == 0x004E4942:
            bin_len = clen
        off += 8 + clen + ((4 - clen % 4) % 4 if clen % 4 else 0)

    g = js
    print("=" * 72)
    print(f"FILE: {os.path.basename(path)}   size={len(data):,} B ({len(data)/1024/1024:.2f} MB)")
    print(f"  glTF version {ver}   bin chunk={bin_len:,} B   json chunk={len(json.dumps(g)):,} B")
    print(f"  extensionsUsed      : {g.get('extensionsUsed')}")
    print(f"  extensionsRequired  : {g.get('extensionsRequired')}")
    print(f"  nodes={len(g.get('nodes',[]))}  meshes={len(g.get('meshes',[]))}  "
          f"materials={len(g.get('materials',[]))}  accessors={len(g.get('accessors',[]))}  "
          f"bufferViews={len(g.get('bufferViews',[]))}  images={len(g.get('images',[]))}  "
          f"textures={len(g.get('textures',[]))}  scenes={len(g.get('scenes',[]))}")

    accs = g.get('accessors', [])
    meshes = g.get('meshes', [])

    total_tris = 0
    total_prims = 0
    total_verts = 0
    tri_hist = []
    for mi, m in enumerate(meshes):
        mt = 0
        for p in m.get('primitives', []):
            total_prims += 1
            mode = p.get('mode', 4)
            if 'indices' in p:
                n = accs[p['indices']]['count']
            else:
                n = accs[p['attributes']['POSITION']]['count']
            if mode == 4:
                t = n // 3
            elif mode in (5, 6):
                t = max(0, n - 2)
            else:
                t = 0
            mt += t
        tri_hist.append((mt, m.get('name', f'mesh{mi}')))
        total_tris += mt
        if m.get('primitives'):
            for p in m['primitives']:
                total_verts += accs[p['attributes']['POSITION']]['count']

    print(f"  TOTAL TRIANGLES     : {total_tris:,}")
    print(f"  TOTAL primitives    : {total_prims:,}")
    print(f"  TOTAL POSITION verts: {total_verts:,}  (sum over primitives, unindexed-shared counts vary)")

    if tri_hist:
        tri_hist.sort(reverse=True)
        print(f"  largest 5 meshes    : " + ", ".join(f"{n}={t:,}" for t, n in tri_hist[:5]))
        print(f"  smallest 3 meshes   : " + ", ".join(f"{n}={t:,}" for t, n in tri_hist[-3:]))
        nonzero = [t for t, _ in tri_hist if t > 0]
        if nonzero:
            import statistics
            print(f"  median tris/mesh    : {statistics.median(nonzero):,.0f}")
            print(f"  mean tris/mesh      : {statistics.mean(nonzero):,.1f}")

    # node naming sample
    nodes = g.get('nodes', [])
    named = [n.get('name') for n in nodes if n.get('name')]
    print(f"  named nodes         : {len(named)}")
    if named:
        print(f"  sample node names   : {named[:8]}")
        print(f"  sample node names   : {named[len(named)//2:len(named)//2+8]}")
    # meshes per node
    withmesh = sum(1 for n in nodes if 'mesh' in n)
    print(f"  nodes carrying mesh : {withmesh}")

    mats = g.get('materials', [])
    if mats:
        print(f"  material names[:6]  : {[m.get('name') for m in mats[:6]]}")

    # primitive attribute keys
    keys = collections.Counter()
    for m in meshes:
        for p in m.get('primitives', []):
            keys[tuple(sorted(p.get('attributes', {}).keys()))] += 1
    print(f"  attribute sets      : {dict(keys)}")

for p in sys.argv[1:]:
    analyze(p)
