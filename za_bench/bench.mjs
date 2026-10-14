import fs from 'node:fs';
import path from 'node:path';
import { performance } from 'node:perf_hooks';

// ---- minimal file:// fetch shim so three's FileLoader/GLTFLoader can read local files
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
const { MeshBVH, computeBoundsTree, disposeBoundsTree, acceleratedRaycast } =
  await import('three-mesh-bvh');

THREE.BufferGeometry.prototype.computeBoundsTree = computeBoundsTree;
THREE.BufferGeometry.prototype.disposeBoundsTree = disposeBoundsTree;
THREE.Mesh.prototype.raycast = acceleratedRaycast;

const threeVer = THREE.REVISION;
console.log(`three r${threeVer}`);

// DRACOLoader normally farms decoding out to a Web Worker (absent in node), and its
// wasm/js glue is fetched by URL. We drive the same in-process path the worker would
// use: module.decodeGeometry(draco, decoder, int8array, taskConfig).
const dracoMod = await import('draco3d');
const decModule = await (dracoMod.default ?? dracoMod).createDecoderModule();
const draco = new DRACOLoader();
let decodeMsTotal = 0;
draco.decodeGeometry = function (buffer, taskConfig) {
  const t = performance.now();
  const geometryData = dracoDecodeGeometry(decModule, new decModule.Decoder(), new Int8Array(buffer), taskConfig);
  const geo = this._createGeometry(geometryData);
  decodeMsTotal += performance.now() - t;
  return Promise.resolve(geo);
};

const loader = new GLTFLoader();
loader.setDRACOLoader(draco);

const files = process.argv.slice(2);

function collectMeshes(root) {
  const out = [];
  root.traverse((o) => { if (o.isMesh) out.push(o); });
  return out;
}

function triCount(geom) {
  const idx = geom.getIndex();
  return idx ? idx.count / 3 : geom.getAttribute('position').count / 3;
}

for (const file of files) {
  console.log('\n' + '='.repeat(78));
  console.log(`FILE: ${path.basename(file)}  (${(fs.statSync(file).size / 1048576).toFixed(2)} MB on disk)`);
  console.log('='.repeat(78));

  const t0 = performance.now();
  const buf = fs.readFileSync(file);
  const arrayBuf = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const gltf = await new Promise((res, rej) =>
    loader.parse(arrayBuf, '', res, rej));
  const tParse = performance.now() - t0;

  const meshes = collectMeshes(gltf.scene);
  let tris = 0, verts = 0, mats = new Set();
  // draco meshes are non-indexed until converted; GLTFLoader leaves them indexed
  for (const m of meshes) {
    tris += triCount(m.geometry);
    const p = m.geometry.getAttribute('position');
    verts += p.count;
    if (m.material) mats.add(m.material.uuid);
  }
  console.log(`parse+decode (Draco, JS decoder) : ${tParse.toFixed(0)} ms`);
  console.log(`meshes: ${meshes.length}   triangles: ${tris.toLocaleString()}   position verts: ${verts.toLocaleString()}`);
  console.log(`distinct materials: ${mats.size}`);

  // GPU memory estimate if expanded to float32 non-indexed
  const bytesFloat32 = tris * 3 * 3 * 4;               // pos (non-indexed worst case)
  const bytesIndexed  = verts * 3 * 4 + tris * 3 * 4;  // pos + uint32 indices
  console.log(`VRAM estimate (indexed, pos+normal+idx): ~${((verts*3*4*2 + tris*3*4)/1048576).toFixed(1)} MB`);

  // ---------- raycast benchmark ----------
  const raycaster = new THREE.Raycaster();
  const origin = new THREE.Vector3(0, 0, 3000);
  const dir = new THREE.Vector3(0, 0, -1).normalize();

  function benchRaycast(label, iterations = 300) {
    // warmup
    for (let i = 0; i < 20; i++) {
      raycaster.set(origin, dir);
      raycaster.intersectObjects(meshes, false);
    }
    const t = performance.now();
    for (let i = 0; i < iterations; i++) {
      // vary the direction so we are not measuring one cached path
      const a = (i / iterations) * Math.PI * 0.6 - 0.3;
      dir.set(Math.sin(a), 0, -Math.cos(a)).normalize();
      raycaster.set(origin, dir);
      raycaster.intersectObjects(meshes, false);
    }
    const dt = performance.now() - t;
    console.log(`  ${label.padEnd(38)} ${(dt / iterations).toFixed(3)} ms/ray  (${iterations} rays in ${dt.toFixed(0)} ms)`);
    return dt / iterations;
  }

  console.log('\n-- raycast: three built-in (no BVH) --');
  const tNoBvh = benchRaycast('Raycaster.intersectObjects(683 meshes)');

  console.log('\n-- building BVH --');
  const tb = performance.now();
  let bvhBuildFailed = 0;
  for (const m of meshes) {
    try { m.geometry.computeBoundsTree(); } catch (e) { bvhBuildFailed++; }
  }
  const tBuild = performance.now() - tb;
  console.log(`  BVH built for ${meshes.length - bvhBuildFailed} geometries in ${tBuild.toFixed(0)} ms`);

  console.log('\n-- raycast: three-mesh-bvh accelerated --');
  const tBvh = benchRaycast('Raycaster + MeshBVH');

  console.log(`\n  SPEEDUP: ${(tNoBvh / tBvh).toFixed(1)}x`);

  // ---------- single merged mesh experiment ----------
  console.log('\n-- merged-single-mesh experiment --');
  const { mergeGeometries } = await import('three/examples/jsm/utils/BufferGeometryUtils.js');
  const geoms = [];
  const nameRanges = [];
  let cursor = 0;
  for (const m of meshes) {
    const g = m.geometry.index ? m.geometry.toNonIndexed() : m.geometry.clone();
    g.applyMatrix4(m.matrixWorld);
    // strip everything but position+normal so merge succeeds
    for (const k of Object.keys(g.attributes)) if (k !== 'position' && k !== 'normal') g.deleteAttribute(k);
    if (!g.getAttribute('normal')) g.computeVertexNormals();
    geoms.push(g);
    const n = g.getAttribute('position').count / 3;
    nameRanges.push({ name: m.name, start: cursor, count: n });
    cursor += n;
  }
  const tm = performance.now();
  let merged = null;
  try {
    merged = mergeGeometries(geoms, false);
  } catch (e) { console.log('  merge failed: ' + e.message); }
  const tMerge = performance.now() - tm;
  if (merged) {
    console.log(`  merged ${geoms.length} geometries -> 1 geometry of ${(merged.getAttribute('position').count/3).toLocaleString()} tris in ${tMerge.toFixed(0)} ms`);
    console.log(`  merged VRAM (pos+normal, non-indexed): ~${((merged.getAttribute('position').count*3*4*2)/1048576).toFixed(1)} MB`);
    const mm = new THREE.Mesh(merged, new THREE.MeshBasicMaterial());
    mm.computeBoundsTree();
    const t2 = performance.now();
    const iters = 300;
    const rc2 = new THREE.Raycaster();
    for (let i = 0; i < iters; i++) {
      const a = (i / iters) * Math.PI * 0.6 - 0.3;
      rc2.set(origin, new THREE.Vector3(Math.sin(a), 0, -Math.cos(a)).normalize());
      const hits = rc2.intersectObject(mm, false);
      if (hits.length && i === 0) {
        const faceIdx = hits[0].faceIndex;
        const owner = nameRanges.find(r => faceIdx >= r.start && faceIdx < r.start + r.count);
        console.log(`  hit -> triangle #${faceIdx} maps to "${owner ? owner.name : '???'}" (range lookup)`);
      }
    }
    const t3 = performance.now();
    console.log(`  merged+BVH raycast: ${((t3-t2)/iters).toFixed(3)} ms/ray`);
    // binary search cost
    const t4 = performance.now();
    for (let i = 0; i < 100000; i++) {
      const f = (i * 37) % cursor;
      let lo = 0, hi = nameRanges.length - 1;
      while (lo < hi) { const mid = (lo + hi) >> 1; if (nameRanges[mid].start <= f) lo = mid + 1; else hi = mid; }
    }
    console.log(`  binary-search name lookup: ${((performance.now()-t4)/100000*1000).toFixed(3)} µs/lookup`);
  }

  const mem = process.memoryUsage();
  console.log(`\n  node heapUsed: ${(mem.heapUsed/1048576).toFixed(0)} MB   rss: ${(mem.rss/1048576).toFixed(0)} MB`);
}
