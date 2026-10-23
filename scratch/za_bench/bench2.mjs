import fs from 'node:fs';
import path from 'node:path';
import { performance } from 'node:perf_hooks';

// ---- file:// fetch shim so three's FileLoader/GLTFLoader can read local files
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

console.log(`three r${THREE.REVISION}  |  three-mesh-bvh (prototype patched)`);

// ---- drive draco in-process (no Web Worker in node)
const dracoMod = await import('draco3d');
const decModule = await (dracoMod.default ?? dracoMod).createDecoderModule();
let decodeMsTotal = 0;
const draco = new DRACOLoader();
draco.decodeGeometry = function (buffer, taskConfig) {
  const t = performance.now();
  const geometryData = dracoDecodeGeometry(decModule, new decModule.Decoder(), new Int8Array(buffer), taskConfig);
  const geo = this._createGeometry(geometryData);
  decodeMsTotal += performance.now() - t;
  return Promise.resolve(geo);
};

const loader = new GLTFLoader();
loader.setDRACOLoader(draco);

const round = (n, d = 3) => Number(n.toFixed(d));

function collectMeshes(root) {
  const out = [];
  root.traverse((o) => { if (o.isMesh) out.push(o); });
  return out;
}
const triCount = (g) => { const i = g.getIndex(); return (i ? i.count : g.getAttribute('position').count) / 3; };

for (const file of process.argv.slice(2)) {
  console.log('\n' + '='.repeat(80));
  console.log(`FILE: ${path.basename(file)}   disk = ${(fs.statSync(file).size / 1048576).toFixed(2)} MB`);
  console.log('='.repeat(80));

  const t0 = performance.now();
  const buf = fs.readFileSync(file);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const gltf = await new Promise((res, rej) => loader.parse(ab, '', res, rej));
  const tParse = performance.now() - t0;

  const allMeshes = collectMeshes(gltf.scene);

  // distinct geometries (glTF reuses one BufferGeometry across identical mesh defs)
  const uniqGeom = new Set();
  let trisAll = 0, trisUniq = 0, vertsUniq = 0;
  for (const m of allMeshes) {
    const t = triCount(m.geometry);
    trisAll += t;
    if (!uniqGeom.has(m.geometry)) {
      uniqGeom.add(m.geometry);
      trisUniq += t;
      vertsUniq += m.geometry.getAttribute('position').count;
    }
  }
  const vramUnique = (vertsUniq * 3 * 4 * 2 + trisUniq * 3 * 4) / 1048576;
  const vramDrawn = (vertsUniq * 3 * 4 * 2 + trisAll * 3 * 4) / 1048576;

  console.log(`parse + Draco decode (JS)   : ${tParse.toFixed(0)} ms   (decode-only ${decodeMsTotal.toFixed(0)} ms)`);
  console.log(`scene mesh nodes            : ${allMeshes.length}`);
  console.log(`distinct BufferGeometry     : ${uniqGeom.size}`);
  console.log(`triangles per frame (drawn) : ${Math.round(trisAll).toLocaleString()}`);
  console.log(`triangles unique in memory  : ${Math.round(trisUniq).toLocaleString()}`);
  console.log(`height-field bbox           : ${JSON.stringify(gltf.scene.children.length)} top-level children`);
  const box = new THREE.Box3().setFromObject(gltf.scene);
  console.log(`model bbox                  : min=${box.min.toArray().map(v=>round(v,1))}  max=${box.max.toArray().map(v=>round(v,1))}`);
  console.log(`  size (mm)                 : ${box.getSize(new THREE.Vector3()).toArray().map(v=>round(v,1))}`);
  console.log(`VRAM if all unique on GPU   : ~${vramUnique.toFixed(1)} MB (pos+normal float32 + uint32 idx)`);
  console.log(`VRAM if expanded (indexed)  : ~${vramDrawn.toFixed(1)} MB`);

  // ---- raycast setup: rays aimed at the model centre from outside the bbox
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const radius = size.length() / 2;

  const raycaster = new THREE.Raycaster();
  const origin = new THREE.Vector3();
  const dir = new THREE.Vector3();

  const N = 240;
  const dirs = [];
  for (let i = 0; i < N; i++) {
    // spiral over the sphere so rays sweep the whole model, not one thin fan
    const y = 1 - (i / (N - 1)) * 2;
    const r = Math.sqrt(Math.max(0, 1 - y * y));
    const theta = Math.PI * (1 + Math.sqrt(5)) * i;
    dirs.push([Math.cos(theta) * r, y, Math.sin(theta) * r]);
  }

  function bench(label, meshes) {
    // warmup
    for (let i = 0; i < 30; i++) {
      dirs[i % N].forEach((v, k) => dir.setComponent(k, v));
      origin.copy(center).addScaledVector(dir, radius * 1.05);
      dir.negate().normalize();
      raycaster.set(origin, dir);
      raycaster.intersectObjects(meshes, false);
    }
    let hits = 0;
    const t = performance.now();
    for (let i = 0; i < N; i++) {
      dirs[i].forEach((v, k) => dir.setComponent(k, v));
      origin.copy(center).addScaledVector(dir, radius * 1.05);
      dir.negate().normalize();
      raycaster.set(origin, dir);
      const res = raycaster.intersectObjects(meshes, false);
      if (res.length) hits++;
    }
    const dt = performance.now() - t;
    const per = dt / N;
    console.log(`  ${label.padEnd(34)} ${round(per).toString().padStart(8)} ms/ray   (${hits}/${N} rays hit, ${dt.toFixed(0)} ms total)`);
    return per;
  }

  console.log('\n-- picking: three.js Raycaster over the mesh graph --');
  const noBvh = bench(`no BVH, ${allMeshes.length} nodes`, allMeshes);

  console.log('\n-- building BVH (one-time) --');
  const tb = performance.now();
  let failed = 0;
  for (const g of uniqGeom) { try { g.computeBoundsTree(); } catch { failed++; } }
  const tBuild = performance.now() - tb;
  console.log(`  ${uniqGeom.size - failed}/${uniqGeom.size} geometries in ${tBuild.toFixed(0)} ms  (${(tBuild / uniqGeom.size).toFixed(1)} ms each)`);
  const withBvh = bench(`BVH, ${allMeshes.length} nodes`, allMeshes);
  console.log(`  -> BVH speedup over naive: ${(noBvh / withBvh).toFixed(1)}x`);

  const mem = process.memoryUsage();
  console.log(`\n  node heapUsed ${(mem.heapUsed / 1048576).toFixed(0)} MB   rss ${(mem.rss / 1048576).toFixed(0)} MB`);
}
