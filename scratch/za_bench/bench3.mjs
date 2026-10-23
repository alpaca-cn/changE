import fs from 'node:fs';
import path from 'node:path';
import { performance } from 'node:perf_hooks';

const realFetch = globalThis.fetch;
globalThis.fetch = async (url, opts) => {
  const u = String(url);
  if (u.startsWith('file://')) {
    const p = decodeURIComponent(new URL(u).pathname.replace(/^\//, ''));
    const buf = fs.readFileSync(p);
    return new Response(buf, { status: 200, headers: { 'Content-Length': String(buf.length) } });
  }
  return realFetch(url, opts);
};

const THREE = await import('three');
const { GLTFLoader } = await import('three/examples/jsm/loaders/GLTFLoader.js');
const { DRACOLoader } = await import('three/examples/jsm/loaders/DRACOLoader.js');
const { decodeGeometry: dracoDecodeGeometry } = await import('./draco-decode.mjs');
const { MeshBVH, acceleratedRaycast } = await import('three-mesh-bvh');
const { mergeGeometries } = await import('three/examples/jsm/utils/BufferGeometryUtils.js');

THREE.Mesh.prototype.raycast = acceleratedRaycast;

const round = (n, d = 3) => Number(n.toFixed(d));
const dracoMod = await import('draco3d');
const decModule = await (dracoMod.default ?? dracoMod).createDecoderModule();
let decodeMsTotal = 0;
const draco = new DRACOLoader();
draco.decodeGeometry = function (buffer, taskConfig) {
  const t = performance.now();
  const gd = dracoDecodeGeometry(decModule, new decModule.Decoder(), new Int8Array(buffer), taskConfig);
  const geo = this._createGeometry(gd);
  decodeMsTotal += performance.now() - t;
  return Promise.resolve(geo);
};
const loader = new GLTFLoader();
loader.setDRACOLoader(draco);

const collectMeshes = (root) => { const o = []; root.traverse((x) => { if (x.isMesh) o.push(x); }); return o; };
const triCount = (g) => { const i = g.getIndex(); return (i ? i.count : g.getAttribute('position').count) / 3; };

function makeRays(center, radius, N = 240) {
  const dirs = [];
  for (let i = 0; i < N; i++) {
    const y = 1 - (i / (N - 1)) * 2;
    const r = Math.sqrt(Math.max(0, 1 - y * y));
    const th = Math.PI * (1 + Math.sqrt(5)) * i;
    dirs.push(new THREE.Vector3(Math.cos(th) * r, y, Math.sin(th) * r).normalize());
  }
  const origin = new THREE.Vector3(), dir = new THREE.Vector3();
  return {
    N, dirs,
    at(i) {
      dir.copy(dirs[i % N]);
      origin.copy(center).addScaledVector(dir, radius * 1.05);
      return { origin: origin.clone(), dir: dir.clone().negate().normalize() };
    },
  };
}

function bench(label, targets, rays, { isSingle = false } = {}) {
  const rc = new THREE.Raycaster();
  for (let i = 0; i < 40; i++) { const { origin, dir } = rays.at(i); rc.set(origin, dir); rc.intersectObjects(targets, false); }
  let hits = 0;
  const t = performance.now();
  for (let i = 0; i < rays.N; i++) {
    const { origin, dir } = rays.at(i);
    rc.set(origin, dir);
    const res = rc.intersectObjects(targets, false);
    if (res.length) hits++;
  }
  const dt = performance.now() - t;
  console.log(`  ${label.padEnd(40)} ${round(dt / rays.N).toString().padStart(8)} ms/ray  (${hits}/${rays.N} hit)`);
  return dt / rays.N;
}

for (const file of process.argv.slice(2)) {
  console.log('\n' + '='.repeat(84));
  console.log(`FILE: ${path.basename(file)}   disk = ${(fs.statSync(file).size / 1048576).toFixed(2)} MB`);
  console.log('='.repeat(84));

  const t0 = performance.now();
  const buf = fs.readFileSync(file);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const gltf = await new Promise((res, rej) => loader.parse(ab, '', res, rej));
  const tParse = performance.now() - t0;

  const meshes = collectMeshes(gltf.scene);
  const uniq = new Set();
  let trisAll = 0, trisUniq = 0, vertsUniq = 0;
  for (const m of meshes) {
    trisAll += triCount(m.geometry);
    if (!uniq.has(m.geometry)) { uniq.add(m.geometry); trisUniq += triCount(m.geometry); vertsUniq += m.geometry.getAttribute('position').count; }
  }
  const box = new THREE.Box3().setFromObject(gltf.scene);
  const center = box.getCenter(new THREE.Vector3());
  const radius = box.getSize(new THREE.Vector3()).length() / 2;
  console.log(`parse + Draco decode (JS)     : ${tParse.toFixed(0)} ms   (decode only ${decodeMsTotal.toFixed(0)} ms)`);
  console.log(`mesh nodes / unique geometry  : ${meshes.length} / ${uniq.size}`);
  console.log(`triangles drawn per frame     : ${Math.round(trisAll).toLocaleString()}`);
  console.log(`triangles unique in memory    : ${Math.round(trisUniq).toLocaleString()}`);
  console.log(`model size (m)                : ${box.getSize(new THREE.Vector3()).toArray().map(v => round(v, 2)).join(' x ')}`);
  console.log(`VRAM (unique, pos+norm+idx32) : ~${((vertsUniq * 3 * 4 * 2 + trisUniq * 3 * 4) / 1048576).toFixed(1)} MB`);
  console.log(`draw calls per frame          : ${meshes.length}${new Set(meshes.map(m => m.material?.uuid)).size === 1 ? '  (all share ONE material -> no material switching, but still 1 draw call each)' : ''}`);

  const rays = makeRays(center, radius);

  console.log('\n[picking A] three.js Raycaster, no acceleration');
  const a = bench(`683-node graph, per-triangle test`, meshes, rays);

  console.log('\n[picking B] build BVH then re-measure (one-time cost)');
  const tb = performance.now();
  for (const m of meshes) if (!m.geometry.boundsTree) m.geometry.boundsTree = new MeshBVH(m.geometry);
  console.log(`  BVH build: ${(performance.now() - tb).toFixed(0)} ms for ${uniq.size} geometries`);
  const b = bench(`same graph, BVH enabled`, meshes, rays);
  console.log(`  -> BVH speedup: ${(a / b).toFixed(1)}x  (${round(a).toFixed(3)} -> ${round(b).toFixed(3)} ms/ray)`);

  console.log('\n[picking C] merged single geometry + BVH (1 draw call)');
  const geoms = []; const ranges = []; let cursor = 0;
  for (const m of meshes) {
    const g = (m.geometry.index ? m.geometry.toNonIndexed() : m.geometry.clone());
    g.applyMatrix4(m.matrixWorld);
    for (const k of Object.keys(g.attributes)) if (k !== 'position' && k !== 'normal') g.deleteAttribute(k);
    if (!g.getAttribute('normal')) g.computeVertexNormals();
    geoms.push(g);
    const n = g.getAttribute('position').count / 3;
    ranges.push({ name: m.name || '(unnamed)', start: cursor, count: n });
    cursor += n;
  }
  const tm = performance.now();
  const merged = mergeGeometries(geoms, false);
  console.log(`  merge: ${geoms.length} geometries -> 1 (${Math.round(cursor).toLocaleString()} tris) in ${(performance.now() - tm).toFixed(0)} ms`);
  const mm = new THREE.Mesh(merged, new THREE.MeshLambertMaterial());
  const tb2 = performance.now();
  const bvh = new MeshBVH(merged);
  console.log(`  BVH build on merged geometry: ${(performance.now() - tb2).toFixed(0)} ms`);
  merged.boundsTree = bvh;
  const c = bench(`1 merged mesh + BVH`, [mm], rays);
  // identity mapping check
  const rc = new THREE.Raycaster();
  const { origin, dir } = rays.at(0);
  rc.set(origin, dir);
  const hit = rc.intersectObject(mm, false)[0];
  if (hit) {
    let lo = 0, hi = ranges.length - 1;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (ranges[mid].start <= hit.faceIndex) lo = mid + 1; else hi = mid; }
    const owner = ranges[lo];
    console.log(`  hit faceIndex ${hit.faceIndex} -> "${owner.name}" (range ${owner.start}..${owner.start + owner.count})  [exact ID mapping works]`);
  }
  const t5 = performance.now();
  for (let i = 0; i < 200000; i++) {
    const f = (i * 37) % cursor;
    let lo = 0, hi = ranges.length - 1;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (ranges[mid].start <= f) lo = mid + 1; else hi = mid; }
  }
  console.log(`  binary-search face->name mapping: ${((performance.now() - t5) / 200000 * 1000).toFixed(2)} µs/lookup`);
  console.log(`  -> merged+BVH vs BVH-graph: ${(b / c).toFixed(2)}x`);

  const mem = process.memoryUsage();
  console.log(`\n  node heapUsed ${(mem.heapUsed / 1048576).toFixed(0)} MB   rss ${(mem.rss / 1048576).toFixed(0)} MB`);
}
